(ns moomoo.server-interface-test
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [moomoo.server-interface :as server]
            [moomoo.user :as user]
            [moomoo.room :as room]
            [moomoo.upload :as upload]
            [moomoo.fixtures :as fixtures]))

(use-fixtures :each
  {:before fixtures/flush-all
   :after  fixtures/flush-all})

(deftest sign-in
  (let [socket-id   "test-socket-id-sign-in"
        room-id     "test-room-id-sign-in"
        username    "test-username-sign-in"
        socket-id-2 "test-socket-id2"
        username-2  "test-username-2"]
    (async done
      (server/sign-in socket-id room-id username
        (fn [user-id users]
          (server/sign-in socket-id-2 room-id username-2
            (fn [user-id-2 users-2]
              (println (keys users-2))
              (is (some #{user-id} (keys users)))
              (is (and (some #{user-id}   (keys users-2))
                       (some #{user-id-2} (keys users-2))))
              (is (not (nil? user-id)))
              (is (not (nil? user-id-2)))
              (user/get-user-id socket-id
                (fn [id]
                  (is (= id user-id))
                  (user/get-user-id socket-id-2
                    (fn [id-2]
                      (is (= id-2 user-id-2))
                      (user/get-username user-id
                        (fn [user]
                          (is (= user username))
                          (is (some #{user-id} (keys users)))
                          (done))))))))))))))

(deftest sign-out
  (let [socket-id "test-socket-id-sign-out"
        room-id   "test-room-id-sign-out"
        username  "test-username-sign-out"]
    (async done
      (server/sign-in socket-id room-id username
        (fn [user-id users]
          (server/sign-out socket-id
            (fn []
              (user/get-user-id socket-id
                (fn [id]
                  (is (nil? id))
                  (user/get-socket-id user-id
                    (fn [id]
                      (is (nil? id))
                      (room/get-users room-id
                        (fn [users]
                          (is (not (some #{user-id} users)))
                          (done))))))))))))))

(deftest chat-message
  (let [message "Woo!"
        room-id "test-room-id"
        username "test-username"
        socket-id "test-socket-id"]
    (async done
      (server/sign-in socket-id room-id username
        (fn [user-id]
          (server/chat-message socket-id user-id message
            (fn [message-fmt]
              (is (:message message-fmt) message)
              (is (:user-id message-fmt) user-id)
              (server/chat-message "BADID" user-id message
                (fn [message-fmt]
                (is (nil? message-fmt))
                (done))))))))))

(deftest hashing
  (let [room-id "wooroom"
        user-id "test-user-id"]
    (async done
      (server/new-hash user-id room-id
        (fn [upload-id uploads-order]
          (is (string? upload-id))
          (is (= upload-id (first uploads-order)))
          (upload/get-uploader upload-id
            (fn [uploader-id]
              (is (= uploader-id user-id))
              (done))))))))