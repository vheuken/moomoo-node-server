(ns moomoo.core
  (:require [cljs.nodejs :as nodejs]
            [clojure.string :as string]
            [cognitect.transit :as transit]
            [moomoo.rooms :as rooms]))

(nodejs/enable-util-print!)

(def port 3001)

(def express (nodejs/require "express"))
(def app (express))
(def server (.Server (nodejs/require "http") app))
(def socketio (nodejs/require "socket.io"))
(def socketio-stream (nodejs/require "socket.io-stream"))
(def file-upload-directory "/tmp/moomoo-uploads")
(def js-uuid (nodejs/require "uuid"))
(def fs (nodejs/require "fs"))

(def io (.listen socketio server))

(defn connection [socket]
  (println (str "User " (.-id socket) " has connected!"))

  (.on socket "disconnect"
    (fn []
      (rooms/disconnect (.-id socket)
        (fn [room-id]
          (rooms/get-all-users room-id
            #(.emit (.to io room-id) "users-list" (clj->js %1)))
          (println (str (.-id socket) " has disconnected!"))))))

  (.on socket "join-room"
    (fn [room-id]
      (.join socket room-id)))

  (.on socket "sign-in"
    (fn [room-id username]
      (rooms/does-room-exist? room-id
        (fn [reply]
          (if-not reply
            (rooms/init-room room-id #(println )))))
      (rooms/set-username room-id (.-id socket) username
        (fn []
          (.emit socket "sign-in-success")
          (rooms/get-all-users room-id
            #(.emit (.to io room-id) "users-list" (clj->js %1)))))))

  (.on socket "chat-message"
    (fn [room message]
      (rooms/get-username room (.-id socket)
        (fn [username]
          (.emit (.to io room) "chat-message" #js {:username username
                                                   :message  message})))))

  (.on socket "ready-to-start"
    (fn []
      (println "Received ready-to-start signal from " (.-id socket))
      (rooms/user-ready-to-start (.-id socket)
        (fn [num-users-ready]
          (rooms/get-room-from-user-id (.-id socket)
            (fn [room]
              (rooms/get-num-of-users room
                (fn [num-users]
                  (if (= num-users num-users-ready)
                    (rooms/set-current-track-position room 0
                      (fn []
                        (.emit (.to io room) "start-track"))))))))))))

  (.on socket "pause"
    (fn [position]
      (rooms/get-room-from-user-id (.-id socket)
        (fn [room]
          (rooms/pause-current-track room position
            (fn []
              (.emit (.to io room) "pause" position)))))))

  (.on socket "resume"
    (fn []
      (rooms/get-room-from-user-id (.-id socket)
        (fn [room]
          (rooms/resume-current-track room
            (fn []
              (.emit (.to io room) "resume")))))))

  (.on (new socketio-stream socket) "file-upload"
    (fn [stream original-filename file-size]
      (let [file-id (.v4 js-uuid)
            filename (subs file-id 0 7)
            absolute-file-path (str file-upload-directory "/" filename)]
        (println (str "Saving file as " absolute-file-path))
        (.pipe stream (.createWriteStream fs absolute-file-path))

        (.on stream "data"
          (fn [data-chunk]
            (rooms/get-room-from-user-id (.-id socket)
              (fn [room]
                (rooms/get-username room (.-id socket)
                  (fn [username]
                    (let [bytes-received (aget (.statSync fs absolute-file-path) "size")]
                      (.emit (.to io room)
                             "file-upload-info"
                             #js {:username      username
                                  :id            file-id
                                  :bytesreceived bytes-received
                                  :totalsize     file-size
                                  :filename      original-filename}))))))))

        (.on stream "end"
          (fn []
            (rooms/set-music-info absolute-file-path
                                  file-id
                                  original-filename
                                  (.-id socket)
              (fn [music-info]
                (rooms/get-room-from-user-id (.-id socket)
                  (fn [room]
                    (.emit (.to io room) "upload-complete" (clj->js music-info))
                    (rooms/get-current-track room
                      (fn [current-track]
                        (println current-track)
                        (if (= current-track -1)
                          (rooms/change-track room 0
                            (fn [track-id]
                              (println "Track changed to " track-id)
                              (.emit (.to io room) "track-change" track-id))))))))))

            (println (str "Successfully uploaded " absolute-file-path)))))))

  (.on socket "file-download-request"
    (fn [track-id]
      (println "Received file download request for " track-id)
      (rooms/get-room-from-user-id (.-id socket)
        (fn [room]
          (rooms/get-music-file room track-id
            (fn [file]
              (let [client-socket (aget (.-connected (.-sockets io)) (.-id socket))
                    stream (.createStream socketio-stream)
                    read-stream (.createReadStream fs file)
                    file-size (.-size (.statSync fs file))]
                (.emit (socketio-stream client-socket) "file-download"
                                                       stream
                                                       track-id
                                                       file-size)
                (.pipe read-stream stream)))))))))

(.on io "connection" connection)

(.set app "views" (string/join ["src/frontend/views"]))
(.set app "view engine" "jade")

(.use app (.static express "public"))
(.get app "/" #(. %2 (sendFile "public/index.html")))

(.get app "/rooms/:id" #(. %2 (render "room" (clj->js {:roomid (.-id (.-params %1))}))))

(defn -main []
  (println (string/join ["Listening on port " port]))
  (.listen server port))

(set! *main-cli-fn* -main)

