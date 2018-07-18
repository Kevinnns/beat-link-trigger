(ns beat-link-trigger.playlist-writer
  "A window that facilitates the creation of comma-separated-value lists
  reporting tracks played over a particular period of time."
  (:require [clojure.data.csv :as csv]
            [seesaw.core :as seesaw]
            [seesaw.mig :as mig]
            [taoensso.timbre :as timbre]
            [beat-link-trigger.expressions :as expressions]
            [beat-link-trigger.util :as util])
  (:import [org.deepsymmetry.beatlink LifecycleListener VirtualCdj DeviceUpdate DeviceUpdateListener CdjStatus
            CdjStatus$TrackType]
           [org.deepsymmetry.beatlink.data MetadataFinder TrackMetadata]
           [java.awt.event WindowEvent]
           [java.util.concurrent TimeUnit]
           [javax.swing JFileChooser]))

(defonce ^{:private true
           :doc "Holds the frame allowing the user to write playlist files."}
  writer-window (atom nil))

(def virtual-cdj
  "The object which can obtained detailed player status information."
  (VirtualCdj/getInstance))

(def metadata-finder
  "The object that can obtain track metadata."
  (MetadataFinder/getInstance))

(defn- make-window-visible
  "Ensures that the playlist writer window is in front, and shown."
  []
  (let [our-frame @writer-window]
    (seesaw/show! our-frame)
    (.toFront our-frame)))

(def ^:private idle-status
  "The status to display when we are not recording a playlist."
  "Idle (not writing a playlist)")

(defn- format-searchable-item
  "Safely translates a (possibly-mising) SearchableItem value into
  either its name or an empty string."
  [item]
  (str (when item (.label item))))

(defn- format-metadata
  "Given a track list entry structure, extracts the title, artist, and
  album if metadata is available, or explains why not as best as
  possible."
  [entry]
  (let [^CdjStatus status       (:cdj-status entry)
        ^TrackMetadata metadata (:metadata entry)]
    (expressions/case-enum (.getTrackType status)

      CdjStatus$TrackType/CD_DIGITAL_AUDIO
      ["Unknown (Audio CD)" "" ""]

      CdjStatus$TrackType/UNANALYZED
      ["Unknown (non-Rekordbox)" "" ""]

      CdjStatus$TrackType/REKORDBOX
      (if metadata
        [(.getTitle metadata)
         (format-searchable-item (.getArtist metadata))
         (format-searchable-item (.getAlbum metadata))]
        ["Unknown (no metadata found" "" ""])

      ["Unknown (unknown track type)" "" ""])))

(defn- build-toggle-handler
  "Creates an event handler for the Start/Stop button."
  [button status file-atom stop-handler frame]
  (fn [_]
    (if @file-atom
      (do  ; Time to stop writing a playlist
        (stop-handler)
        (seesaw/config! button :text "Start")
        (seesaw/config! status :text idle-status)
        (reset! file-atom nil))
      (when-let [file (util/confirm-overwrite-file
                       (seesaw.chooser/choose-file frame :type :save
                                                   :filters [["Playlist CSV files" ["csv"]]]
                                                   :all-files? false)
                       "csv"
                       frame)]
        (try
          (with-open [writer (clojure.java.io/writer file)]
            (csv/write-csv writer [["Title" "Artist" "Album" "Player" "Started" "Stopped" "Play Time"]]))
          (reset! file-atom file)
          (seesaw/config! button :text "Stop")
          (seesaw/config! status :text (str "Writing to " (.getName file)))
          (catch Throwable t
            (timbre/error t "Problem creating playlist file" file)))))))

(defn- write-entry-if-played-enough
  [min-play-seconds playlist-file player-number entry]
  (when entry
    (let [now (System/currentTimeMillis)
          played (.toSeconds TimeUnit/MILLISECONDS (- now (:started entry)))]
      (when (and (>= played min-play-seconds)
                 playlist-file)
        (let [[title artist album] (format-metadata entry)]
          (try
            (with-open [writer (clojure.java.io/writer playlist-file :append true)]
              (csv/write-csv writer [[title artist album player-number
                                      (str (java.util.Date. (:started entry))) (str (java.util.Date. now)) played]]))
            (catch Throwable t
              (timbre/error t "Problem adding entry to playlist file" playlist-file))))))))

(defn- track-changed?
  "Checks whether a device update indicates that a different track is
  playing than the one we have been timing."
  [^CdjStatus new-status entry]
  (when entry
    (let [^CdjStatus old-status (:cdj-status entry)]
      (or (not= (.getTrackType old-status) (.getTrackType new-status))
          (not= (.getTrackNumber old-status) (.getTrackNumber new-status))
          (and (= (.getTrackType old-status) CdjStatus$TrackType/REKORDBOX)
               (or (not= (.getTrackSourcePlayer old-status) (.getTrackSourcePlayer new-status))
                   (not= (.getTrackSourceSlot old-status) (.getTrackSourceSlot new-status))
                    (not= (.getRekordboxId old-status) (.getRekordboxId new-status))))))))

(defn- build-update-listener
  "Creates the update listener which keeps track of all playing tracks
  and writes playlist entries at the appropriate time. Returns a tuple
  consisting of that listener and a function that can be called to
  write out playlist entries for any tracks that have currently been
  playing long enough because the playlist file is being closed."
  [time-spinner file-atom]
  (let [playing-tracks (atom {})]
    [(reify DeviceUpdateListener
        (received [this device-update]
          (when (instance? CdjStatus device-update)
            (let [now              (System/currentTimeMillis)
                  cdj-status       ^CdjStatus device-update
                  player-number    (.getDeviceNumber cdj-status)
                  min-play-seconds (seesaw/value time-spinner)
                  playlist-file    @file-atom]
              (swap! playing-tracks update player-number
                     (fn [old-entry]
                       (if (.isPlaying cdj-status)
                         (if (track-changed? cdj-status old-entry)
                           (do  ; Write out old entry if it had played enough, and swap in our new one.
                             (write-entry-if-played-enough min-play-seconds playlist-file player-number old-entry)
                             {:started now
                              :cdj-status cdj-status})
                           (if old-entry
                             (if (:metadata old-entry)
                               old-entry  ; We are still playing a track we already have metadata for.
                               (assoc old-entry :metadata (.getLatestMetadataFor metadata-finder player-number)))
                             {:started now  ; We have a new entry, there was nothing there before.
                              :cdj-status cdj-status}))
                         (do  ; Not playing, so clear any existing entry, but write it out if it had played enough.
                           (when old-entry
                             (write-entry-if-played-enough min-play-seconds playlist-file player-number old-entry))
                           nil))))))))
     (fn [] ; Closing the playlist, write out any entries that deserve it.
       (let [min-play-seconds (seesaw/value time-spinner)
             playlist-file    @file-atom]
         (when playlist-file
           (doseq [[player-number entry] @playing-tracks]
             (write-entry-if-played-enough min-play-seconds playlist-file player-number entry)))))]))

(defn- create-window
  "Creates the playlist writer window."
  [trigger-frame]
  (try
    (let [playlist-file (atom nil)
          time-spinner  (seesaw/spinner :id :time :model (seesaw/spinner-model 10 :from 0 :to 60))
          toggle-button (seesaw/button :id :start :text "Start")
          status-label  (seesaw/label :id :status :text idle-status)
          panel         (mig/mig-panel
                         :background "#ccc"
                         :items [[(seesaw/label :text "Minimum Play Time:") "align right"]
                                 [time-spinner]
                                 [(seesaw/label :text "seconds") "align left, wrap"]

                                 [(seesaw/label :text "Status:") "align right"]
                                 [status-label "span, grow, wrap 15"]

                                 [(seesaw/label :text "")]
                                 [toggle-button "span 2"]
                                 ])
          root            (seesaw/frame :title "Playlist Writer"
                                        :content panel
                                        :on-close :dispose)
          [update-listener
           close-handler] (build-update-listener time-spinner playlist-file)
          stop-listener   (reify LifecycleListener
                            (started [this sender])  ; Nothing for us to do, we exited as soon a stop happened anyway.
                            (stopped [this sender]  ; Close our window if VirtualCdj gets shut down (we went offline).
                              (seesaw/invoke-later
                               (.dispatchEvent root (WindowEvent. root WindowEvent/WINDOW_CLOSING)))))]
      (.addUpdateListener virtual-cdj update-listener)
      (.addLifecycleListener virtual-cdj stop-listener)
      (seesaw/listen root :window-closed (fn [e]
                                           (.removeUpdateListener virtual-cdj update-listener)
                                           (.removeLifecycleListener virtual-cdj stop-listener)
                                           (close-handler)
                                           (reset! writer-window nil)))
      (seesaw/listen toggle-button :action (build-toggle-handler toggle-button status-label playlist-file
                                                                 close-handler root))
      (seesaw/pack! root)
      #_(.setResizable root false)
      (reset! writer-window root)
      (when-not (.isRunning virtual-cdj) (.stopped stop-listener virtual-cdj)))  ; In case we went offline during setup.
    (catch Exception e
      (timbre/error e "Problem creating Playlist Writer window."))))

(defn show-window
  "Make the Playlist Writer window visible, creating it if necessary."
  [trigger-frame]
  (locking writer-window
    (when-not @writer-window (create-window trigger-frame)))
  (make-window-visible))