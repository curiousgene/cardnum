(ns game-test.core
  (:require [game.core :as core]
            [game.utils :as utils :refer [make-cid]]
            [cardnum.cards :refer [all-cards]]
            [game-test.utils :refer [load-cards]]
            [clojure.test :refer :all]))

;;; Click action functions
(defn new-game
  "Init a new game using given contestant and challenger. Keep starting hands (no mulligan) and start Contestant's turn."
  ([contestant challenger] (new-game contestant challenger nil))
  ([contestant challenger {:keys [mulligan start-as dont-start-turn dont-start-game] :as args}]
    (let [state (core/init-game
                   {:gameid 1
                    :players [{:side "Contestant"
                               :deck {:identity (@all-cards (:identity contestant))
                                      :cards (:deck contestant)}}
                              {:side "Challenger"
                               :deck {:identity (@all-cards (:identity challenger))
                                      :cards (:deck challenger)}}]})]
      (when-not dont-start-game
        (if (#{:both :contestant} mulligan)
          (core/resolve-prompt state :contestant {:choice "Mulligan"})
          (core/resolve-prompt state :contestant {:choice "Keep"}))
        (if (#{:both :challenger} mulligan)
          (core/resolve-prompt state :challenger {:choice "Mulligan"})
          (core/resolve-prompt state :challenger {:choice "Keep"}))
        (when-not dont-start-turn (core/start-turn state :contestant nil)))
      state)))

(defn load-all-cards [tests]
  (when (empty? @all-cards)
    (core/reset-card-defs)
    (reset! all-cards (into {} (map (juxt :title identity) (map #(assoc % :cid (make-cid)) (load-cards))))))
  (tests))
(use-fixtures :once load-all-cards)

(defn reset-card-defs [card-type tests]
  (core/reset-card-defs card-type)
  (tests))

;;; Card related functions
(defn find-card
  "Return a card with given title from given sequence"
  [title from]
  (some #(when (= (:title %) title) %) from))

(defn card-ability
  "Trigger a card's ability with its 0-based index. Refreshes the card argument before
  triggering the ability."
  ([state side card ability] (card-ability state side card ability nil))
  ([state side card ability targets]
   (core/play-ability state side {:card (core/get-card state card)
                                  :ability ability
                                  :targets targets})))

(defn card-subroutine
  "Trigger a piece of character's subroutine with the 0-based index."
  ([state side card ability] (card-subroutine state side card ability nil))
  ([state side card ability targets]
   (core/play-subroutine state side {:card (core/get-card state card)
                                     :subroutine ability
                                     :targets targets})))

(defn card-side-ability
  ([state side card ability] (card-side-ability state side card ability nil))
  ([state side card ability targets]
   (let [ab {:card (core/get-card state card)
             :ability ability
             :targets targets}]
     (if (= :contestant side)
                (core/play-contestant-ability state side ab)
                (core/play-challenger-ability state side ab)))))

(defn get-character
  "Get placed character protecting locale by position. If no pos, get all character on the locale."
  ([state locale]
   (get-in @state [:contestant :locales locale :characters]))
  ([state locale pos]
   (get-in @state [:contestant :locales locale :characters pos])))

(defn get-content
  "Get card in a locale by position. If no pos, get all cards in the locale."
  ([state locale]
   (get-in @state [:contestant :locales locale :content]))
  ([state locale pos]
   (get-in @state [:contestant :locales locale :content pos])))

(defn get-resource
  "Get non-hosted resource by position. If no pos, get all placed resources."
  ([state] (get-in @state [:challenger :rig :resource]))
  ([state pos]
   (get-in @state [:challenger :rig :resource pos])))

(defn get-hazard
  "Get hazard by position. If no pos, get all placed hazard."
  ([state] (get-in @state [:challenger :rig :hazard]))
  ([state pos]
   (get-in @state [:challenger :rig :hazard pos])))

(defn get-radicle
  "Get non-hosted radicle by position. If no pos, get all placed radicles."
  ([state] (get-in @state [:challenger :rig :radicle]))
  ([state pos]
   (get-in @state [:challenger :rig :radicle pos])))

(defn get-challenger-facedown
  "Get non-hosted challenger facedown by position. If no pos, get all challenger facedown placed cards."
  ([state] (get-in @state [:challenger :rig :facedown]))
  ([state pos]
   (get-in @state [:challenger :rig :facedown pos])))

(defn get-discarded
  "Get discarded card by position. If no pos, selects most recently discarded card."
  ([state side] (get-discarded state side (-> @state side :discard count dec)))
  ([state side pos]
   (get-in @state [side :discard pos])))

(defn get-scored
  "Get a card from the score area. Can find by name or index.
  If no index or name provided, gets all scored cards."
  ([state side] (get-in @state [side :scored]))
  ([state side x]
   (if (number? x)
     ;; Find by index
     (get-in @state [side :scored x])
     ;; Find by name
     (when (string? x)
       (find-card x (get-in @state [side :scored]))))))

(def get-counters utils/get-counters)

(defn play-from-hand
  "Play a card from hand based on its title. If placing a Contestant card, also indicate
  the locale to place into with a string."
  ([state side title] (play-from-hand state side title nil))
  ([state side title locale]
   (core/play state side {:card (find-card title (get-in @state [side :hand]))
                          :locale locale})))


;;; Run functions
(defn play-run-event
  "Play a run event with a replace-access effect on an unprotected locale.
  Advances the run timings to the point where replace-access occurs."
  ([state card locale] (play-run-event state card locale true))
  ([state card locale show-prompt]
   (let [card (if (map? card) card (find-card card (get-in @state [:challenger :hand])))]
     (core/play state :challenger {:card card})
     (is (= [locale] (get-in @state [:run :locale])) "Correct locale is run")
     (is (get-in @state [:run :run-effect]) "There is a run-effect")
     (core/no-action state :contestant nil)
     (core/successful-run state :challenger nil)
     (if show-prompt
       (is (get-in @state [:challenger :prompt]) "A prompt is shown")
       (is (not (get-in @state [:challenger :prompt])) "A prompt is not shown"))
     (is (get-in @state [:run :successful]) "Run is marked successful"))))

(defn run-on
  "Start run on specified locale."
  [state locale]
  (core/click-run state :challenger {:locale locale}))

(defn run-continue
  "No action from contestant and continue for challenger to proceed in current run."
  [state]
  (core/no-action state :contestant nil)
  (core/continue state :challenger nil))

(defn run-phase-43
  "Ask for triggered abilities phase 4.3"
  [state]
  (core/contestant-phase-43 state :contestant nil)
  (core/successful-run state :challenger nil))

(defn run-successful
  "No action from contestant and successful run for challenger."
  [state]
  (core/no-action state :contestant nil)
  (core/successful-run state :challenger nil))

(defn run-jack-out
  "Jacks out in run."
  [state]
  (core/jack-out state :challenger nil))

(defn run-empty-locale
  "Make a successful run on specified locale, assumes no character in place."
  [state locale]
  (run-on state locale)
  (run-successful state))


;;; Misc functions
(defn score-agenda
  "Take clicks and credits needed to advance and score the given agenda."
  ([state _ card]
   (let [title (:title card)
         advancementcost (:advancementcost card)]
     (core/gain state :contestant :click advancementcost :credit advancementcost)
     (is (= advancementcost (get-counters (core/get-card state card) :advancement)))
     (core/score state :contestant {:card (core/get-card state card)})
     (is (find-card title (get-scored state :contestant))))))

(defn last-log-contains?
  [state content]
  (some? (re-find (re-pattern content)
                  (-> @state :log last :text))))

(defn second-last-log-contains?
  [state content]
  (some? (re-find (re-pattern content)
                  (-> @state :log butlast last :text))))

(defn discard-from-hand
  "Discard specified card from hand of specified side"
  [state side title]
  (core/discard state side (find-card title (get-in @state [side :hand]))))

(defn discard-radicle
  "Discard specified card from rig of the challenger"
  [state title]
  (core/discard state :challenger (find-card title (get-in @state [:challenger :rig :radicle]))))

(defn starting-hand
  "Moves all cards in the player's hand to their draw pile, then moves the specified card names
  back into the player's hand."
  [state side cards]
  (doseq [c (get-in @state [side :hand])]
    (core/move state side c :deck))
  (doseq [ctitle cards]
    (core/move state side (find-card ctitle (get-in @state [side :deck])) :hand)))

(defn accessing
  "Checks to see if the challenger has a prompt accessing the given card title"
  [state title]
  (= title (-> @state :challenger :prompt first :card :title)))

(defn play-and-score
  "Play an agenda from the hand into a new locale and score it. Unlike score-agenda, spends a click."
  [state title]
  (play-from-hand state :contestant title "New party")
  (score-agenda state :contestant (get-content state (keyword (str "party" (:rid @state))) 0)))
