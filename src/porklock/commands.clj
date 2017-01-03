(ns porklock.commands
  (:use [porklock.pathing]
        [porklock.config])
  (:require [clj-jargon.init :as jg]
            [clj-jargon.item-info :as info]
            [clj-jargon.item-ops :as ops]
            [clj-jargon.metadata :as meta]
            [clj-jargon.permissions :as perms]
            [clojure.java.io :as io]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure-commons.file-utils :as ft])
  (:import [java.io File]                                            ; needed for cursive type navigation
           [org.irods.jargon.core.exception DuplicateDataException]
           [org.irods.jargon.core.transfer TransferStatus]))         ; needed for cursive type navigation


(def porkprint (partial println "[porklock] "))

(defn init-jargon
  [cfg-path]
  (load-config-from-file cfg-path)
  (jg/init (irods-host)
           (irods-port)
           (irods-user)
           (irods-pass)
           (irods-home)
           (irods-zone)
           (irods-resc)))

(defn retry
  "Attempt calling (func) with args a maximum of 'times' times if an error occurs.
   Adapted from a stackoverflow solution: http://stackoverflow.com/a/12068946"
  [max-attempts func & args]
  (let [result (try+
                 {:value (apply func args)}
                 (catch Object e
                   (porkprint "Error calling a function." max-attempts "attempts remaining:" e)
                   (if-not (pos? max-attempts)
                     (throw+ e)
                     {:exception e})))]
    (if-not (:exception result)
      (:value result)
      (recur (dec max-attempts) func args))))


(defn fix-meta
  [m]
  (cond
    (= (count m) 3) m
    (= (count m) 2) (conj m "default-unit")
    (= (count m) 1) (concat m ["default-value" "default-unit"])
    :else           []))

(defn avu?
  [cm path attr value]
  (filter #(= value (:value %)) (meta/get-attribute cm path attr)))


(defn- ensure-metadatum-applied
  [cm destination avu]
  (try+
    (apply meta/add-metadata cm destination avu)
    (catch DuplicateDataException _
      (porkprint "Strange." destination "already has the metadatum" (str avu ".")))))


(defn- apply-metadatum
  [cm destination avu]
  (porkprint "Might be adding metadata to" destination avu)
  (let [existent-avu (avu? cm destination (first avu) (second avu))]
    (porkprint "AVU?" destination existent-avu)
    (when (empty? existent-avu)
      (porkprint "Adding metadata" (first avu) (second avu) destination)
      (ensure-metadatum-applied cm destination avu))))


(defn apply-metadata
  [cm destination meta]
  (let [tuples (map fix-meta meta)
        dest   (ft/rm-last-slash destination)]
    (porkprint "Metadata tuples for" destination "are" tuples)
    (when (pos? (count tuples))
      (doseq [tuple tuples]
        (porkprint "Size of tuple" tuple "is" (count tuple))
        (when (= (count tuple) 3)
          (apply-metadatum cm dest tuple))))))

(defn home-folder?
  [zone full-path]
  (let [parent (ft/dirname full-path)]
    (= parent (ft/path-join "/" zone "home"))))

(defn iput-status
  "Callback function for the overallStatus function for a TransferCallbackListener."
  [^TransferStatus transfer-status]
  (let [exc (.getTransferException transfer-status)]
    (if-not (nil? exc)
      (throw exc))))

(defn iput-status-cb
  "Callback function for the statusCallback function of a TransferCallbackListener."
  [^TransferStatus transfer-status]
  (porkprint "-------")
  (porkprint "iput status update:")
  (porkprint "\ttransfer state:" (.getTransferState transfer-status))
  (porkprint "\ttransfer type:" (.getTransferType transfer-status))
  (porkprint "\tsource path:" (.getSourceFileAbsolutePath transfer-status))
  (porkprint "\tdest path:" (.getTargetFileAbsolutePath transfer-status))
  (porkprint "\tfile size:" (.getTotalSize transfer-status))
  (porkprint "\tbytes transferred:" (.getBytesTransfered transfer-status))
  (porkprint "\tfiles to transfer:" (.getTotalFilesToTransfer transfer-status))
  (porkprint "\tfiles skipped:" (.getTotalFilesSkippedSoFar transfer-status))
  (porkprint "\tfiles transferred:" (.getTotalFilesTransferredSoFar transfer-status))
  (porkprint "\ttransfer host:" (.getTransferHost transfer-status))
  (porkprint "\ttransfer zone:" (.getTransferZone transfer-status))
  (porkprint "\ttransfer resource:" (.getTargetResource transfer-status))
  (porkprint "-------")
  (when-let [exc (.getTransferException transfer-status)]
    (throw exc))
  ops/continue)

(defn- iput-force-cb
  "Callback function for the transferAsksWhetherToForceOperation function of a
   TransferCallbackListener."
   [abs-path collection?]
   (porkprint "force iput of " abs-path ". collection?: " collection?)
   ops/yes-for-all)

(def tcl (ops/transfer-callback-listener iput-status iput-status-cb iput-force-cb))

(defn- parent-exists?
  "Returns true if the parent directory exists or is /iplant/home"
  [cm dest-dir]
  (if (home-folder? (:zone cm) dest-dir)
    true
    (info/exists? cm (ft/dirname dest-dir))))

(defn- parent-writeable?
  "Returns true if the parent directorty is writeable or is /iplant/home."
  [cm user dest-dir]
  (if (home-folder? (:zone cm) dest-dir)
    true
    (perms/is-writeable? cm user (ft/dirname dest-dir))))

(defn- relative-destination-paths
  [options]
  (relative-dest-paths (files-to-transfer options)
                       (ft/abs-path (:source options))
                       (:destination options)))

(def error? (atom false))

(defn- upload-files
  [cm options]
  (doseq [[src dest] (seq (relative-destination-paths options))]
    (let [dir-dest (ft/dirname dest)]
      (if-not (or (.isFile (io/file src))
                  (.isDirectory (io/file src)))
        (porkprint "Path" src "is neither a file nor a directory.")
        (do
          ;;; It's possible that the destination directory doesn't
          ;;; exist yet in iRODS, so create it if it's not there.
          (porkprint "Creating all directories in iRODS down to" dir-dest)
          (when-not (info/exists? cm dir-dest)
            (ops/mkdirs cm dir-dest))

          ;;; The destination directory needs to be tagged with AVUs
          ;;; for the App and Execution.
          (porkprint "Applying metadata to" dir-dest)
          (apply-metadata cm dir-dest (:meta options))

          (try+
            (retry 10 ops/iput cm src dest tcl)

            ;;; Apply the App and Execution metadata to the newly uploaded file/directory.
            (porkprint "Applying metadata to" dest)
            (apply-metadata cm dest (:meta options))
            (catch Object err
              (porkprint "iput failed:" err)
              (reset! error? true))))))))

(def script-loc
  (memoize (fn []
             (ft/dirname (ft/abs-path (System/getenv "SCRIPT_LOCATION"))))))

(defn- upload-nfs-files
  [cm options]
  (if (and (System/getenv "SCRIPT_LOCATION") (not (:skip-parent-meta options)))
    (let [dest       (ft/path-join (:destination options) "logs")
          exclusions (set (exclude-files-from-dir (merge options {:source (script-loc)})))]
      (porkprint "Exclusions:\n" exclusions)
      (doseq [fileobj (file-seq (clojure.java.io/file (script-loc)))]
        (let [src       (.getAbsolutePath fileobj)
              dest-path (ft/path-join dest (ft/basename src))]
          (try+
           (when-not (or (.isDirectory fileobj) (contains? exclusions src))
             (retry 10 ops/iput cm src dest tcl)
             (apply-metadata cm dest-path (:meta options)))
           (catch [:error_code "ERR_BAD_EXIT_CODE"] err
             (porkprint "Command exited with a non-zero status:" err)
             (reset! error? true))))))))

(defn iput-command
  "Runs the iput icommand, tranferring files from the --source
   to the remote --destination."
  [options]
  (jg/with-jargon (init-jargon (:config options)) :client-user (:user options) [cm]
    ;;; The parent directory needs to actually exist, otherwise the dest-dir
    ;;; doesn't exist and we can't safely recurse up the tree to create the
    ;;; missing directories. Can't even check the perms safely if it doesn't
    ;;; exist.
    (when-not (parent-exists? cm (:destination options))
      (porkprint (ft/dirname (:destination options)) "does not exist.")
      (System/exit 1))

    ;;; Need to make sure the parent directory is writable just in
    ;;; case we end up having to create the destination directory under it.
    (when-not (parent-writeable? cm (:user options) (:destination options))
      (porkprint (ft/dirname (:destination options)) "is not writeable.")
      (System/exit 1))

    ;;; Now we can make sure the actual dest-dir is set up correctly.
    (when-not (info/exists? cm (:destination options))
      (porkprint "Path" (:destination options) "does not exist. Creating it.")
      (ops/mkdir cm (:destination options)))

    (upload-files cm options)

    (when-not (:skip-parent-meta options)
      (porkprint "Applying metadata to" (:destination options))
      (apply-metadata cm (:destination options) (:meta options))
      (doseq [fileobj (file-seq (info/file cm (:destination options)))]
        (apply-metadata cm (.getAbsolutePath fileobj) (:meta options))))

    ;;; Transfer files from the NFS mount point into the logs
    ;;; directory of the destination
    (upload-nfs-files cm options)

    (if @error?
      (throw (Exception. "An error occurred tranferring files into iRODS. Please check the above logs for more information.")))))

(defn apply-input-metadata
  [cm user fpath meta]
  (if-not (info/is-dir? cm fpath)
    (if (perms/owns? cm user fpath)
      (apply-metadata cm fpath meta))
    (doseq [f (file-seq (info/file cm fpath))]
      (let [abs-path (.getAbsolutePath f)]
        (if (perms/owns? cm user abs-path)
          (apply-metadata cm abs-path meta))))))

(defn iget-command
  "Runs the iget icommand, retrieving files from --source
   to the local --destination."
  [options]
  (jg/with-jargon (init-jargon (:config options)) :client-user (:user options) [cm]
    (apply-input-metadata cm (:user options) (ft/rm-last-slash (:source options)) (:meta options))
    (retry 10 ops/iget cm (:source options) (:destination options) tcl)))
