(ns web.game
  (:require [web.ws :as ws]
            [web.lobby :refer [all-games old-states already-in-game? spectator?] :as lobby]
            [web.utils :refer [proj-dir my-value-reader response]]
            [web.stats :as stats]
            [game.main :as main]
            [game.core :as core]
            [cardnum.utils :refer [side-from-str]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.data.json :as j-data]
            [monger.json]
            [crypto.password.bcrypt :as bcrypt]
            [clj-time.core :as t]))
(import '[java.io RandomAccessFile])

(defn append-to-json [file-name new-json-text]
  (let [raf (RandomAccessFile. file-name "rw")
        lock (.lock (.getChannel raf))    ;; avoid concurrent invocation across processes
        current-length (.length raf)]
    (if (= current-length 0)
      (do
        (.writeBytes raf "{\n")           ;; On the first write, prepend a "{"
        (.writeBytes raf new-json-text)   ;; ...before the data...
        (.writeBytes raf "\n}\n"))        ;; ...and a final "\n}\n"
      (do
        (.seek raf (- current-length 2))  ;; move to before the last "\n}\n"
        (.writeBytes raf ",\n")           ;; put a comma where that "\n" used to be
        (.writeBytes raf new-json-text)   ;; ...then the new data...
        (.writeBytes raf "\n}\n")))       ;; ...then a new "\n}\n"
    (.close lock)
    (.close raf)))

(defn send-state-diffs!
  "Sends diffs generated by game.main/public-diffs to all connected clients."
  [{:keys [gameid players spectators] :as game}
   {:keys [type challenger-diff contestant-diff spect-diff] :as diffs}]
  (doseq [{:keys [ws-id side] :as pl} players]
    (ws/send! ws-id [:meccg/diff (json/generate-string
                                       {:gameid gameid
                                        :diff (if (= side "Contestant")
                                                contestant-diff
                                                challenger-diff)})]))
  (doseq [{:keys [ws-id] :as pl} spectators]
    (ws/send! ws-id [:meccg/diff (json/generate-string
                                       {:gameid gameid
                                        :diff spect-diff})])))

(defn send-state!
  "Sends full states generated by game.main/public-states to all connected clients."
  ([game states]
   (send-state! :meccg/state game states))

  ([event
    {:keys [gameid players spectators] :as game}
    {:keys [type challenger-state contestant-state spect-state] :as states}]
   (doseq [{:keys [ws-id side] :as pl} players]
     (ws/send! ws-id [event (json/generate-string
                              {:gameid gameid
                               :state (if (= side "Contestant")
                                        contestant-state
                                        challenger-state)})]))
   (doseq [{:keys [ws-id] :as pl} spectators]
     (ws/send! ws-id [event (json/generate-string
                              {:gameid gameid
                               :state spect-state})]))))

(defn handle-game-save
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str save-pref] :as msg}  :?data}]
  (let [gameid (java.util.UUID/fromString gameid-str)
        {:keys [started players state] :as game} (lobby/game-for-id gameid)
        side (some #(when (= client-id (:ws-id %)) (:side %)) players)]
    (when (lobby/player? client-id gameid)
      (let [id_usern1 (get-in @(:state game) [:contestant :user :username])
            id_usern2 (get-in @(:state game) [:challenger :user :username])]
        (spit (str "all-states/" id_usern1 "/" save-pref "s.json")
              (json/generate-string @(:state game)))
        (when (not (nil? id_usern2))
          (spit (str "all-states/" id_usern2 "/" save-pref "s.json")
                (json/generate-string @(:state game)))))
      )))

(defn swap-and-send-state!
  "Updates the old-states atom with the new game state, then sends a :meccg/state
  message to game clients."
  [{:keys [gameid state] :as game}]
  (swap! old-states assoc gameid @state)
  (send-state! game (main/public-states state)))

(defn swap-and-send-diffs!
  "Updates the old-states atom with the new game state, then sends a :meccg/diff
  message to game clients."
  [{:keys [gameid state] :as game}]
  (let [old-state (get @old-states gameid)]
    (when (and state @state)
      (swap! old-states assoc gameid @state)
      (send-state-diffs! game (main/public-diffs old-state state)))))

(defn- active-game?
  [gameid-str client-id]
  (if (nil? gameid-str)
    false
    (let [gameid (java.util.UUID/fromString gameid-str)
          game-from-gameid (lobby/game-for-id gameid)
          game-from-clientid (lobby/game-for-client client-id)]
      (and game-from-clientid
           game-from-gameid
           (= (:gameid game-from-clientid) (:gameid game-from-gameid))))))

(defn handle-game-start-new
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id}]
  (when-let [{:keys [players gameid started] :as game} (lobby/game-for-client client-id)]
    (when (and (lobby/first-player? client-id gameid)
               (not started))
      (let [strip-deck (fn [player] (-> player
                                        (update-in [:deck] #(select-keys % [:_id :identity]))
                                        (update-in [:deck :identity] #(select-keys % [:title :alignment :trimCode]))))
            stripped-players (mapv strip-deck players)
            game (as-> game g
                       (assoc g :started true
                                :original-players stripped-players
                                :ending-players stripped-players
                                :last-update (t/now))
                       (assoc g :state (core/init-game g))
                       (update-in g [:players] #(mapv strip-deck %)))]
        (let [id_title1 (get-in @(:state game) [:contestant :identity :title])
              id_scode1 (get-in @(:state game) [:contestant :identity :set_code])
              id_image1 (get-in @(:state game) [:contestant :identity :ImageName])
              id_align1 (get-in @(:state game) [:contestant :identity :alignment])
              id_usern1 (get-in @(:state game) [:contestant :user :username])
              id_title2 (get-in @(:state game) [:challenger :identity :title])
              id_scode2 (get-in @(:state game) [:challenger :identity :set_code])
              id_image2 (get-in @(:state game) [:challenger :identity :ImageName])
              id_align2 (get-in @(:state game) [:challenger :identity :alignment])
              id_usern2 (get-in @(:state game) [:challenger :user :username])
              game-name (str id_usern1 "'s game w/" id_usern2)
              game-date (t/now)]

          (when (and (not (= nil id_usern2))
                  (not (.exists (io/file (str proj-dir "/all-pre-g/" id_usern2)))))
            (do
              (.mkdir (io/file (io/file (str proj-dir "/all-pre-g/" id_usern2))))
              (.mkdir (io/file (io/file (str proj-dir "/all-saves/" id_usern2))))
              (.mkdir (io/file (io/file (str proj-dir "/all-states/" id_usern2))))
              ))

          (loop [k 0]
            (if (and
                  (not (.exists (io/file (str proj-dir "/all-saves/" id_usern1 "/" id_usern1 " vs " id_usern2 "-" k "g.json"))))
                  (not (.exists (io/file (str proj-dir "/all-states/" id_usern1 "/" id_usern1 " vs " id_usern2 "-" k "s.json"))))
                  (not (.exists (io/file (str proj-dir "/all-saves/" id_usern2 "/" id_usern1 " vs " id_usern2 "-" k "g.json"))))
                  (not (.exists (io/file (str proj-dir "/all-states/" id_usern2 "/" id_usern1 " vs " id_usern2 "-" k "s.json")))))
              (do
                (append-to-json (str proj-dir "/all-pre-g/" id_usern1 "/" id_usern1 "'s game.json")
                                (str "  \"id_title1\" : \"" id_title1 "\",\n"
                                     "  \"id_scode1\" : \"" id_scode1 "\",\n"
                                     "  \"id_image1\" : \"" id_image1 "\",\n"
                                     "  \"id_align1\" : \"" id_align1 "\",\n"
                                     "  \"id_usern1\" : \"" id_usern1 "\",\n"
                                     "  \"id_title2\" : \"" id_title2 "\",\n"
                                     "  \"id_scode2\" : \"" id_scode2 "\",\n"
                                     "  \"id_image2\" : \"" id_image2 "\",\n"
                                     "  \"id_align2\" : \"" id_align2 "\",\n"
                                     "  \"id_usern2\" : \"" id_usern2 "\",\n"
                                     "  \"game-name\" : \"" game-name "\",\n"
                                     "  \"game-save\" : \"" id_usern1 " vs " id_usern2 "-" k "\",\n"
                                     "  \"game-date\" : \"" game-date "\""
                                     ))

                (spit (str "all-saves/" id_usern1 "/" id_usern1 " vs " id_usern2 "-" k "g.json")
                      (slurp (str "all-pre-g/" id_usern1 "/" id_usern1 "'s game.json")))
                (when (not (= nil id_usern2))
                  (spit (str "all-saves/" id_usern2 "/" id_usern1 " vs " id_usern2 "-" k "g.json")
                        (slurp (str "all-pre-g/" id_usern1 "/" id_usern1 "'s game.json"))))
                (io/delete-file (str proj-dir "/all-pre-g/" id_usern1 "/" id_usern1 "'s game.json"))
                (spit (str "all-states/" id_usern1 "/" id_usern1 " vs " id_usern2 "-" k "s.json")
                      (json/generate-string @(:state game)))
                (ws/send! client-id [:meccg/relay {:save (str id_usern1 " vs " id_usern2 "-" k)
                                                   :resumed false}])
                (when (not (= nil id_usern2))
                  (spit (str "all-states/" id_usern2 "/" id_usern1 " vs " id_usern2 "-" k "s.json")
                      (json/generate-string @(:state game))))
                ) ;end of do
              (recur (+ k 1))) ;end of if
            ) ; end of loop
          ) ;end of my let
        (swap! all-games assoc gameid game)
        (swap! old-states assoc gameid @(:state game))
        (stats/game-started game)
        (lobby/refresh-lobby :update gameid)
        (send-state! :meccg/start game (main/public-states (:state game)))
        ) ; end of their let
      )))

(defn handle-game-start-load
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [save-pref] :as msg}         :?data}]
  (when-let [{:keys [title players gameid started] :as game} (lobby/game-for-client client-id)]
    (when (and (lobby/first-player? client-id gameid)
               (not started))
      (let [strip-deck (fn [player] (-> player
                                        (update-in [:deck] #(select-keys % [:_id :identity]))
                                        (update-in [:deck :identity] #(select-keys % [:title :alignment]))))
            stripped-players (mapv strip-deck players)
            ;_ (println (str save-pref))
            init (j-data/read-str
                   (slurp (str "all-states/" username "/" save-pref "s.json"))
                   :value-fn my-value-reader
                   :key-fn keyword)
            game (as-> game g
                       (assoc g :started true
                                :original-players stripped-players
                                :ending-players stripped-players
                                :last-update (t/now))
                       (assoc g :state (core/load-game init))
                       (update-in g [:players] #(mapv strip-deck %)))
            ]

        (let [id_usern1 (get-in @(:state game) [:contestant :user :username])
              id_usern2 (get-in @(:state game) [:challenger :user :username])]
          (when (or
                (not (.exists (io/file (str proj-dir "/all-saves/" id_usern1 "/" save-pref "g.json"))))
                (not (.exists (io/file (str proj-dir "/all-states/" id_usern1 "/" save-pref "s.json")))))
            (spit (str "all-saves/" id_usern1 "/" save-pref "g.json")
                  (slurp (str "all-saves/" username "/" save-pref "g.json")))
            (spit (str "all-states/" id_usern1 "/" save-pref "s.json")
                  (slurp (str "all-states/" username "/" save-pref "s.json"))))
          (when (and (not (nil? id_usern2))
                   (or (not (.exists (io/file (str proj-dir "/all-saves/" id_usern2 "/" save-pref "g.json"))))
                       (not (.exists (io/file (str proj-dir "/all-states/" id_usern2 "/" save-pref "s.json"))))))
            (spit (str "all-saves/" id_usern2 "/" save-pref "g.json")
                  (slurp (str "all-saves/" username "/" save-pref "g.json")))
            (spit (str "all-states/" id_usern2 "/" save-pref "s.json")
                  (slurp (str "all-states/" username "/" save-pref "s.json"))))
          )
        (swap! all-games assoc gameid game)
        (swap! old-states assoc gameid @(:state game))
        (stats/game-started game)
        (lobby/refresh-lobby :update gameid)
        (send-state! :meccg/start game (main/public-states (:state game)))
        ))))

(defn handle-game-leave
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str] :as msg}        :?data}]
  (let [{:keys [started players gameid state] :as game} (lobby/game-for-client client-id)]
    (when (and started state)
      (lobby/remove-user client-id gameid)
      (when-let [game (lobby/game-for-id gameid)]
        ; The game will not exist if this is the last player to leave.
        (main/handle-notification state (str username " has left the game."))
        (swap-and-send-diffs! (lobby/game-for-id gameid))))))

(defn handle-game-rejoin
  [{{{:keys [username _id] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid]}   :?data
    reply-fn                            :?reply-fn}]
  (let [{:keys [original-players started players state] :as game} (lobby/game-for-id gameid)]
    (if (and started
             (< (count players) 2)
             (some #(= _id (:_id %)) (map :user original-players)))
      (let [player (lobby/join-game user client-id gameid nil)
            side (keyword (str (.toLowerCase (:side player)) "-state"))]
        (main/handle-rejoin state (:user player))
        (lobby/refresh-lobby :update gameid)
        (ws/send! client-id [:lobby/select {:gameid gameid
                                            :started started
                                            :state (json/generate-string
                                                     (side (main/public-states (:state game))))}])
        (swap-and-send-state! (lobby/game-for-id gameid))))))

(defn handle-game-concede
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str] :as msg}        :?data}]
  (when (active-game? gameid-str client-id)
    (let [gameid (java.util.UUID/fromString gameid-str)
          {:keys [started players state] :as game} (lobby/game-for-id gameid)
          side (some #(when (= client-id (:ws-id %)) (:side %)) players)]
      (when (lobby/player? client-id gameid)
        (main/handle-concede state (side-from-str side))
        (swap-and-send-diffs! game)))))

(defn handle-mute-spectators
  [{{{:keys [username] :as user} :user}          :ring-req
    client-id                                    :client-id
    {:keys [gameid-str mute-state] :as msg}      :?data}]
  (when (active-game? gameid-str client-id)
    (let [gameid (java.util.UUID/fromString gameid-str)
          {:keys [started state] :as game} (lobby/game-for-id gameid)
          message (if mute-state "muted" "unmuted")]
      (when (lobby/player? client-id gameid)
        (swap! all-games assoc-in [gameid :mute-spectators] mute-state)
        (main/handle-notification state (str username " " message " specatators."))
        (lobby/refresh-lobby :update gameid)
        (swap-and-send-diffs! game)
        (ws/broadcast-to! (lobby/lobby-clients gameid)
                          :games/diff
                          {:diff {:update {gameid (lobby/game-public-view (lobby/game-for-id gameid))}}})))))

(defn handle-game-action
  [{{{:keys [username] :as user} :user}        :ring-req
    client-id                                  :client-id
    {:keys [gameid-str command args] :as msg}      :?data}]
  (when (active-game? gameid-str client-id)
    (let [gameid (java.util.UUID/fromString gameid-str)
          {:keys [players state] :as game} (lobby/game-for-id gameid)
          side (some #(when (= client-id (:ws-id %)) (:side %)) players)
          spectator (spectator? client-id gameid)]
      (if (and state side)
        (do
          (main/handle-action user command state (side-from-str side) args)
          (swap! all-games assoc-in [gameid :last-update] (t/now))
          (swap-and-send-diffs! game))
        (when (not spectator)
          (do
            (println "handle-game-action unknown state or side")
            (println "\tGameID:" gameid)
            (println "\tGameID by ClientID:" (:gameid (lobby/game-for-client client-id)))
            (println "\tClientID:" client-id)
            (println "\tSide:" side)
            (println "\tPlayers:" (map #(select-keys % [:ws-id :side]) players))
            (println "\tSpectators" (map #(select-keys % [:ws-id]) (:spectators game)))
            (println "\tCommand:" command)
            (println "\tArgs:" args "\n")))))))

(defn handle-game-watch
  "Handles a watch command when a game has started."
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid password options]}   :?data
    reply-fn                            :?reply-fn}]
  (if-let [{game-password :password state :state started :started :as game}
           (lobby/game-for-id gameid)]
    (when (and user game (lobby/allowed-in-game game user) state @state)
      (if-not started
        false ; don't handle this message, let lobby/handle-game-watch.
        (if (and (not (already-in-game? user game))
                 (or (empty? game-password)
                     (bcrypt/check password game-password)))
          (let [{:keys [spect-state]} (main/public-states state)]
            ;; Add as a spectator, inform the client that this is the active game,
            ;; add a chat message, then send full states to all players.
            ; TODO: this would be better if a full state was only sent to the new spectator, and diffs sent to the existing players.
            (lobby/spectate-game user client-id gameid)
            (main/handle-notification state (str username " joined the game as a spectator."))
            (ws/send! client-id [:lobby/select {:gameid gameid
                                                :started started}])
            (swap-and-send-state! (lobby/game-for-id gameid))
            (when reply-fn (reply-fn 200))
            true)
          (when reply-fn
            (reply-fn 403)
            false))))
    (when reply-fn
      (reply-fn 404)
      false)))

(defn handle-game-say
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str msg]}                :?data}]
  (when (active-game? gameid-str client-id)
    (let [gameid (java.util.UUID/fromString gameid-str)
          {:keys [state mute-spectators] :as game} (lobby/game-for-id gameid)
          {:keys [side user]} (lobby/player? client-id gameid)]
      (if (and state side user)
        (do (main/handle-say state (cardnum.utils/side-from-str side) user msg)
          (swap-and-send-diffs! game))
        (let [{:keys [user]} (lobby/spectator? client-id gameid)]
          (when (and user (not mute-spectators))
            (main/handle-say state :spectator user msg)
            (swap! all-games assoc-in [gameid :last-update] (t/now))
            (try
              (swap-and-send-diffs! game)
              (catch Exception ex
                (println (str "handle-game-say exception:" (.getMessage ex) "\n"))))))))))

(defn handle-game-typing
  [{{{:keys [username] :as user} :user} :ring-req
    client-id                           :client-id
    {:keys [gameid-str typing]}             :?data}]
  (when (active-game? gameid-str client-id)
    (let [gameid (java.util.UUID/fromString gameid-str)
          {:keys [state] :as game} (lobby/game-for-id gameid)
          {:keys [side user]} (lobby/player? client-id gameid)]
      (when (and state side user)
        (main/handle-typing state (cardnum.utils/side-from-str side) user typing)
        (try
          (swap-and-send-diffs! game)
          (catch Exception ex
            (println (str "handle-game-typing exception:" (.getMessage ex) "\n"))))))))

(defn handle-ws-close [{{{:keys [username] :as user} :user} :ring-req
                        client-id                           :client-id}]
  (when-let [{:keys [gameid state] :as game} (lobby/game-for-client client-id)]
    (lobby/remove-user client-id (:gameid game))
    (when-let [game (lobby/game-for-id gameid)]
      ; The game will not exist if this is the last player to leave.
      (main/handle-notification state (str username " has disconnected."))
      (swap-and-send-diffs! game))))

(ws/register-ws-handlers!
  :meccg/save handle-game-save
  :meccg/load handle-game-start-load
  :meccg/start handle-game-start-new
  :meccg/action handle-game-action
  :meccg/leave handle-game-leave
  :meccg/rejoin handle-game-rejoin
  :meccg/concede handle-game-concede
  :meccg/mute-spectators handle-mute-spectators
  :meccg/say handle-game-say
  :meccg/typing handle-game-typing
  :lobby/watch handle-game-watch
  :chsk/uidport-close handle-ws-close)
