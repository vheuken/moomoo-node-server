(ns moomoo-frontend.core)

(defonce room-id (.getAttribute (. js/document (getElementById "roomid")) "data"))
(defonce room (str "room:" room-id))
(defonce socket (js/io))
(defonce app-state (atom {:logged-in? false
                          :users []
                          :messages []
                          :upload-progress nil
                          :data-uploaded 0
                          :current-file-download nil
                          :music-files {}
                          :message-received false
                          :file-downloading? false
                          :num-of-queued-requests 0
                          :current-track 0
                          :current-sound nil
                          :current-sound-id "current-song"
                          :music-tags []}))

(enable-console-print!)

(.on socket "connect" #(.emit socket "join_room" room))

(.hide (js/$ "#file_upload_input"))

(.submit (js/$ "#message-form")
  (fn []
    (.emit socket "chat message" room (.val (js/$ "#m")))
    (.val (js/$ "#m") "")
    false))

; TODO: We want to get rid of this at some point
;       and handle things more like the om tutorial handles things
(.submit (js/$ "#username-form")
  (fn []
    (swap! app-state assoc :logged-in? true)
    (.emit socket "set_username" room (.val (js/$ "#username")))
    (.show (js/$ "#file_upload_input"))
    false))

(.on socket "chat message"
  (fn [message]
    (swap! app-state assoc :messages (conj (:messages @app-state) message))
    (swap! app-state assoc :message-received true)))

(.on socket "userslist"
  (fn [users]
    (swap! app-state assoc :users users)))

(defn request-new-file []
  (println "Requesting new file...")
  (.emit socket "new-file-request"))

(.on socket "file-upload-notification"
  (fn []
    (println "Received file upload notification!")
    (cond (:file-downloading? @app-state)
      (+ 1 (:num-of-queued-requests @app-state))
      :else (request-new-file))))

(defn on-finish []
  (println "Song has finished!"))

(defn play-sound [sound-data]
  (if-not (nil? (:current-sound @app-state))
    (.destroySound js/soundManager (:current-sound-id @app-state)))
  (swap! app-state assoc :current-sound
    (.createSound js/soundManager #js {:id   (:current-sound-id @app-state)
                                       :type "audio/mpeg"
                                       :url  sound-data}))

  (.play (:current-sound @app-state)
         #js {:onfinish on-finish})
  (swap! app-state assoc :current-track (+ 1 (:current-track @app-state))))


(.on (new js/ss socket) "file-to-client"
  (fn [stream tags]
    (swap! app-state assoc :music-tags
      (conj (:music-tags @app-state) (str (.-artist tags) " - " (.-title tags))))
    (println (:music-tags @app-state))
    (swap! app-state assoc :file-downloading? true)
    (.on stream "data"
      (fn [blob-chunk]
        (let [stream-id (.-id stream)]
          (println stream-id)
          (if (nil? (get (:music-files @app-state) stream-id))
            (swap! app-state assoc :music-files
              (merge (:music-files @app-state) {stream-id (new js/Blob)})))
          (swap! app-state assoc :music-files
            (merge (:music-files @app-state) {stream-id
              (new js/Blob #js [(get (:music-files @app-state) stream-id) blob-chunk]
                           #js {:type "audio/mpeg"})})))))
    (.on stream "end"
      (fn []
        (let [reader (new js/FileReader)
              blob   (get (:music-files @app-state) (.-id stream))]
          (.readAsDataURL reader blob)
          (swap! app-state assoc :file-downloading? false)
          (request-new-file)
          (println (str "Number of music files downloaded "
                        (count (:music-files @app-state))))
          (if (nil? (:current-sound @app-state))
            (set! (.-onloadend reader) #(play-sound (.-result reader)))))))))

(.change (js/$ "#file_upload_input")
  (fn [e]
    (let [file (aget (.-files (.-target e)) 0)
          stream (.createStream js/ss)
          blob-stream (.createBlobReadStream js/ss file)]
      (println "File uploading!")

      (swap! app-state assoc :upload-progress 0)
      (swap! app-state assoc :data-uploaded 0)

      (.on blob-stream "data"
        (fn [data-chunk]
          (let [size (+ (.-length data-chunk) (:data-uploaded @app-state))]
            (swap! app-state assoc :upload-progress (Math/floor (* 100 (/ size (.-size file)))))
            (swap! app-state assoc :data-uploaded size))))

      (.on blob-stream "end"
        (fn []
          (println "Upload successful!")
          (swap! app-state assoc :upload-progress nil)
          (swap! app-state assoc :data-uploaded 0)))

      (.emit (js/ss socket) "file" stream)
      (.pipe blob-stream stream))))

