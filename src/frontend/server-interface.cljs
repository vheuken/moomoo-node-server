(ns moomoo-frontend.server-interface
  (:require [moomoo-frontend.core :as core]
            [moomoo-frontend.tracks :as tracks]
            [moomoo-frontend.app-state :as app-state]
            [moomoo-frontend.player :as player]
            [moomoo-frontend.uploads :as uploads]
            [clojure.string :as string]))

(.on app-state/socket "reconnect"
  (fn []
    (if-let [user-id (:user-id @app-state/app-state)]
      (.emit app-state/socket "socket-id-change" user-id))))

(.on app-state/socket "socket-id-change-success"
  (fn []
    (println "Successfulyl changed socket id")
    (swap! app-state/app-state assoc :reconnected? true)))

(.on app-state/socket "sign-in-success"
  (fn [userId]
    (println "Successfully signed in!")
    (println "User-id:" userId)
    (swap! app-state/app-state
           merge
           {:signed-in? true
            :user-id    userId})))

(.on app-state/socket "chat-message"
  (fn [message]
    (println "Received chat-message signal:" message)
    (swap! app-state/app-state assoc :messages (conj (:messages @app-state/app-state) message))
    ; should probably replace "message-received?" flag with some type of watcher
    (swap! app-state/app-state assoc :message-received? true)))

(.on app-state/socket "users-list"
  (fn [users]
    (println "Received users-list signal: " users)
    (let [users (js->clj users)]
      (swap! app-state/app-state assoc :users users))))

(.on app-state/socket "file-upload-info"
  (fn [file-upload-info]
    (if (= (.-totalsize file-upload-info) (.-bytesreceived file-upload-info))
      (swap! app-state/app-state
             assoc
             :current-uploads-info
             (dissoc (:current-uploads-info @app-state/app-state) (.-id file-upload-info)))
      (swap! app-state/app-state
             assoc
             :current-uploads-info
             (merge (:current-uploads-info @app-state/app-state)
                    {(.-id file-upload-info) file-upload-info})))))

(.on app-state/socket "resume" player/resume!)

(.on app-state/socket "pause"
  (fn [position]
    (player/pause!)
    (player/set-position! position)))

(.on app-state/socket "position-change" player/set-position!)

(.on app-state/socket "clear-songs" tracks/clear-tracks!)

(.on app-state/socket "delete-track" tracks/delete-track!)

(.on app-state/socket "start-track"
  (fn [file-url position]
    (let [file-url (str (first (string/split (.-href (.-location js/window))
                                             #"/rooms"))
                        file-url)

          current-sound-id (:current-sound-id @app-state/app-state)]

      (player/load-track! file-url
                          current-sound-id
                          #(player/play-track! current-sound-id
                                               position
                                               core/on-finish)))))

(.on app-state/socket "track-change"
  (fn [track-id sound-id]
    (println "Received track-change signal:"
             "track-id:" track-id
             "sound-id:" sound-id)
    (let [last-current-track-id (:current-track-id @app-state/app-state)
          last-current-sound-id (:current-sound-id @app-state/app-state)]

      (if-not (nil? last-current-sound-id)
        (player/destroy-track last-current-sound-id)))

      (swap! app-state/app-state
             merge
             {:current-track-id track-id
              :current-sound-id sound-id})

      (.emit app-state/socket "ready-to-start" sound-id)))

(.on app-state/socket "hotjoin-music-info"
  (fn [room-track-id-map
       room-music-info
       track-order
       current-track-id
       current-sound-id
       paused?]
    (println "Received room state:"
             "room-music-info:" room-music-info
             "track-order:" track-order
             "current-track-id:" current-track-id
             "current-sound-id:" current-sound-id
             "track-id-hashes:" room-track-id-map)

    (swap! app-state/app-state
           merge
           {:track-id-hashes (js->clj room-track-id-map)
            :track-order (js->clj track-order)
            :music-info (vec (map #(clj->js %1) (js->clj room-music-info)))
            :current-track-id current-track-id
            :current-sound-id current-sound-id})

    (if paused?
      (player/pause!))

    (if-not (nil? current-sound-id)
      (.emit app-state/socket "ready-to-start" current-sound-id))))

(.on app-state/socket "set-loop"
  (fn [looping?]
    (println "Received set-loop signal with looping?:" looping?)
    (swap! app-state/app-state assoc :looping? looping?)))

(.on app-state/socket "hash-found"
  (fn [id file-hash]
    (println "File exists on server. Hash: " file-hash)
    (swap! app-state/app-state
           assoc
           :file-hashes
           (dissoc (:file-hashes @app-state/app-state) file-hash))))

(.on app-state/socket "hash-not-found"
  (fn [id file-hash]
    (println "File does not exist on server. Will upload. Hash: " file-hash)
    (let [file (get (:file-hashes @app-state/app-state) file-hash)]
      (swap! app-state/app-state
             assoc
             :file-hashes
             (dissoc (:file-hashes @app-state/app-state) file-hash))
      (println file file-hash)
      (uploads/upload-file! file id))))

(.on app-state/socket "user-muted"
  (fn [user-id]
    (println "Received mute-user signal for" user-id)
    (swap! app-state/app-state
           assoc
           :users
           (merge (:users @app-state/app-state)
                  {user-id (merge (get (:users @app-state/app-state) user-id)
                                  {"muted" true})}))))


(.on app-state/socket "user-unmuted"
  (fn [socket-id]
    (println "Received umute-user signal for" socket-id)
    (swap! app-state/app-state
           assoc
           :users
           (merge (:users @app-state/app-state)
                  {socket-id (merge (get (:users @app-state/app-state) socket-id)
                                    {"muted" false})}))))

(.on app-state/socket "upload-cancelled"
  (fn [id]
    (println "upload cancelled!")
    (let [current-uploads-info (:current-uploads-info @app-state/app-state)
          uploads (:uploads @app-state/app-state)]
      (swap! app-state/app-state
             merge
             {:current-uploads-info (dissoc current-uploads-info id)
              :uploads (dissoc uploads id)}))))

(.on app-state/socket "track-order-change"
  (fn [track-order]
    (swap! app-state/app-state assoc :track-order (js->clj track-order))))

(.on app-state/socket "upload-complete"
  (fn [id music-info track-order track-id-hashes]
    (println "Received upload-complete signal:"
             "music-info:" music-info
             "track-order:" track-order)
    (swap! app-state/app-state
           merge
           {:music-info (conj (:music-info @app-state/app-state) music-info)
            :track-id-hashes (js->clj track-id-hashes)
            :room-file-hashes (dissoc (:room-file-hashes @app-state/app-state) id)
            :track-order (js->clj track-order)})))

(.on app-state/socket "upload-slots-change"
  (fn [new-upload-slots]
    (swap! app-state/app-state assoc :upload-slots new-upload-slots)))

(.on app-state/socket "lastfm-auth"
  (fn [status username]
    (if (= status "success")
      (do
        (swap! app-state/app-state
               assoc
               :lastfm-username
               username)
        (js/alert (str "Logged into LastFM as " username)))
      (js/alert "Failed to log into LastfM"))))

(.on app-state/socket "new-uploads-order"
  (fn [uploads-order]
    (swap! app-state/app-state
           assoc
           :room-uploads-order
           (js->clj uploads-order))))

(.on app-state/socket "hash-progress"
  (fn [id filename current-chunk chunks]
    (swap! app-state/app-state
           assoc
           :room-file-hashes
           (assoc (:room-file-hashes @app-state/app-state)
                  id
                  {:current-chunk current-chunk
                   :chunks chunks
                   :name filename}))))


(.on app-state/socket "start-hashing"
  (fn [client-id id]
    (when-let [file ((:files-to-check @app-state/app-state) client-id)]
      (swap! app-state/app-state
             assoc
             :files-to-check
             (dissoc (:files-to-hash @app-state/app-state)
                     client-id))
      (js/md5File file
        (fn [current-chunk chunks]
          (.emit app-state/socket
                 "hash-progress"
                 id
                 (.-name file)
                 current-chunk
                 chunks))
        (fn [file-hash]
          (swap! app-state/app-state
                 assoc
                 :file-hashes
                 (merge {file-hash file}
                        (:file-hashes @app-state/app-state)))
         (.emit app-state/socket "check-hash" id file-hash))))))
