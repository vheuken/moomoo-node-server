(ns moomoo.core
  (:require [cljs.nodejs :as nodejs]
            [moomoo.socketio-interface :as socketio-interface]
            #_[moomoo.rooms :as rooms]
            #_[moomoo.config :as config]
            #_[moomoo.watcher :as watcher]))

(nodejs/enable-util-print!)

(defonce port 3001)

(defonce express (nodejs/require "express"))
(defonce app (express))
(defonce server (.Server (nodejs/require "http") app))
(defonce redis-client (.createClient (nodejs/require "redis")))
(defonce util (nodejs/require "util"))

(socketio-interface/initialize! server #js {"heartbeat interval" 5
                                            "heartbeat timeout"  15})

(.set app "views" "src/frontend/views")
(.set app "view engine" "jade")

(.use app (.static express "public"))
(.get app "/" #(. %2 (sendFile "public/index.html")))

(.get app "/rooms/:id" #(. %2 (render "room" #js {:roomid (.-id (.-params %1))
                                                  ;:maxuploadslots (config/data "max-upload-slots")
                                                  ;:defaultuploadslots (config/data "default-upload-slots")
                                                  ;:lastfmkey (config/data "lastfm-api-key")
                                                  :allowedfileextensions nil #_(reduce (fn [a b] (str a "," b))
                                                                                 (rooms/allowed-file-extensions (.-id (.-params %1))))})))

(defn -main []
  #_(config/load-file! "config.toml")

  (println (str "Listening on port " port))
  #_(watcher/watch-directories! (config/data "music-watch-dirs"))
  (socketio-interface/start-listening!)
  (.listen server port))

(set! *main-cli-fn* -main)
