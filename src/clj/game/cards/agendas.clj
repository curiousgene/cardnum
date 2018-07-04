(ns game.cards.agendas
  (:require [game.core :refer :all]
            [game.utils :refer :all]
            [game.macros :refer [effect req msg wait-for continue-ability]]
            [clojure.string :refer [split-lines split join lower-case includes? starts-with?]]
            [clojure.stacktrace :refer [print-stack-trace]]
            [cardnum.utils :refer [str->int]]
            [cardnum.cards :refer [all-cards]]))

(defn character-boost-agenda [subtype]
  (letfn [(count-character [contestant]
            (reduce (fn [c server]
                      (+ c (count (filter #(and (has-subtype? % subtype)
                                                (rezzed? %))
                                          (:characters server)))))
                    0 (flatten (seq (:servers contestant)))))]
    {:msg (msg "gain " (count-character contestant) " [Credits]")
     :interactive (req true)
     :effect (effect (gain-credits (count-character contestant))
                     (update-all-character))
     :swapped {:effect (req (update-all-character state side))}
     :events {:pre-character-strength {:req (req (has-subtype? target subtype))
                                 :effect (effect (character-strength-bonus 1 target))}}}))

(def card-definitions
  {"15 Minutes"
   {:abilities [{:cost [:click 1] :msg "shuffle 15 Minutes into R&D"
                 :label "Shuffle 15 Minutes into R&D"
                 :effect (req (let [contestant-agendas (get-in contestant [:scored])
                                    agenda-owner (if (some #(= (:cid %) (:cid card)) contestant-agendas) :contestant :challenger)]
                                (gain-agenda-point state agenda-owner (- (:agendapoints card))))
                              ; refresh agendapoints to 1 before shuffle in case it was modified by e.g. The Board
                              (move state :contestant (dissoc (assoc card :agendapoints 1) :seen :rezzed) :deck {:front true})
                              (shuffle! state :contestant :deck))}]
    :flags {:has-abilities-when-stolen true}}

   "Accelerated Beta Test"
   (letfn [(abt [n i]
             (if (pos? i)
               {:async true
                :prompt "Select a piece of Character from the Temporary Zone to install"
                :choices {:req #(and (= (:side %) "Contestant")
                                     (character? %)
                                     (= (:zone %) [:play-area]))}
                :effect (req (wait-for (contestant-install state side target nil
                                                     {:no-install-cost true :install-state :rezzed-no-cost})
                                       (let [card (get-card state card)]
                                         (unregister-events state side card)
                                         (if (not (:shuffle-occurred card))
                                           (if (< n i)
                                             (continue-ability state side (abt (inc n) i) card nil)
                                             (do (doseq [c (get-in @state [:contestant :play-area])]
                                                   (system-msg state side "trashes a card")
                                                   (trash state side c {:unpreventable true}))
                                                 (effect-completed state side eid)))
                                           (do (doseq [c (get-in @state [:contestant :play-area])]
                                                 (move state side c :deck))
                                               (shuffle! state side :deck)
                                               (effect-completed state side eid))))))
                :cancel-effect (req (doseq [c (get-in @state [:contestant :play-area])]
                                      (system-msg state side "trashes a card")
                                      (trash state side c {:unpreventable true})))}
               {:prompt "None of the cards are character. Say goodbye!"
                :choices ["I have no regrets"]
                :effect (req (doseq [c (get-in @state [:contestant :play-area])]
                               (system-msg state side "trashes a card")
                               (trash state side c {:unpreventable true})))}))]
     {:interactive (req true)
      :optional {:prompt "Look at the top 3 cards of R&D?"
                 :yes-ability {:async true
                               :msg "look at the top 3 cards of R&D"
                               :effect (req (register-events state side
                                                             {:contestant-shuffle-deck
                                                              {:effect (effect (update! (assoc card :shuffle-occurred true)))}}
                                                             card)
                                            (let [n (count (filter character? (take 3 (:deck contestant))))]
                                              (doseq [c (take (min (count (:deck contestant)) 3) (:deck contestant))]
                                                (move state side c :play-area))
                                              (continue-ability state side (abt 1 n) card nil)))}}})

   "Advanced Concept Hopper"
   {:events
    {:run
     {:req (req (first-event? state side :run))
      :effect (effect (show-wait-prompt :challenger "Contestant to use Advanced Concept Hopper")
                      (continue-ability
                        {:player :contestant
                         :prompt "Use Advanced Concept Hopper to draw 1 card or gain 1 [Credits]?"
                         :once :per-turn
                         :choices ["Draw 1 card" "Gain 1 [Credits]" "No action"]
                         :effect (req (case target
                                        "Gain 1 [Credits]"
                                        (do (gain-credits state :contestant 1)
                                            (system-msg state :contestant (str "uses Advanced Concept Hopper to gain 1 [Credits]")))
                                        "Draw 1 card"
                                        (do (draw state :contestant)
                                            (system-msg state :contestant (str "uses Advanced Concept Hopper to draw 1 card")))
                                        "No action"
                                        (system-msg state :contestant (str "doesn't use Advanced Concept Hopper")))
                                      (clear-wait-prompt state :challenger)
                                      (effect-completed state side eid))}
                        card nil))}}}

   "Ancestral Imager"
   {:events {:jack-out {:msg "do 1 net damage"
                        :effect (effect (damage :net 1))}}}

   "AR-Enhanced Security"
   {:events {:challenger-trash {:once :per-turn
                            :async true
                            :req (req (some #(card-is? % :side :contestant) targets))
                            :msg "give the Challenger a tag for trashing a Contestant card"
                            :effect (effect (tag-challenger :challenger eid 1))}}}

   "Armed Intimidation"
   {:async true
    :effect (effect (show-wait-prompt :contestant "Challenger to suffer 5 meat damage or take 2 tags")
                    (continue-ability :challenger
                      {:async true
                       :choices ["Suffer 5 meat damage" "Take 2 tags"]
                       :prompt "Choose Armed Intimidation score effect"
                       :effect (req (clear-wait-prompt state :contestant)
                                    (case target
                                      "Suffer 5 meat damage"
                                      (do (damage state :challenger eid :meat 5 {:card card :unboostable true})
                                          (system-msg state :challenger "chooses to suffer 5 meat damage from Armed Intimidation"))
                                      "Take 2 tags"
                                      (do (tag-challenger state :challenger eid 2 {:card card})
                                          (system-msg state :challenger "chooses to take 2 tags from Armed Intimidation"))))}
                      card nil))}

   "Armored Servers"
   {:implementation "Challenger must trash cards manually when required"
    :effect (effect (add-counter card :agenda 1))
    :silent (req true)
    :abilities [{:counter-cost [:agenda 1]
                 :req (req (:run @state))
                 :msg "make the Challenger trash a card from their grip to jack out or break subroutines for the remainder of the run"}]}

   "AstroScript Pilot Resource"
   {:effect (effect (add-counter card :agenda 1))
    :silent (req true)
    :abilities [{:counter-cost [:agenda 1] :msg (msg "place 1 advancement token on "
                                                      (card-str state target))
                 :choices {:req can-be-advanced?}
                 :effect (effect (add-prop target :advance-counter 1 {:placed true}))}]}

   "Award Bait"
   {:flags {:rd-reveal (req true)}
    :access {:async true
             :req (req (not-empty (filter #(can-be-advanced? %) (all-installed state :contestant))))
             :effect (effect (show-wait-prompt :challenger "Contestant to place advancement tokens with Award Bait")
                             (continue-ability
                               {:async true
                                :choices ["0", "1", "2"]
                                :prompt "How many advancement tokens?"
                                :effect (req (let [c (str->int target)]
                                               (continue-ability
                                                 state side
                                                 {:choices {:req can-be-advanced?}
                                                  :msg (msg "place " c " advancement tokens on " (card-str state target))
                                                  :cancel-effect (req (clear-wait-prompt state :challenger)
                                                                      (effect-completed state side eid))
                                                  :effect (effect (add-prop :contestant target :advance-counter c {:placed true})
                                                                  (clear-wait-prompt :challenger))} card nil)))}
                              card nil))}}

   "Bacterial Resourceming"
   (letfn [(hq-step [remaining to-trash to-hq]
             {:async true
              :prompt "Select a card to move to HQ"
              :choices (conj (vec remaining) "Done")
              :effect (req (if (= "Done" target)
                             (do
                               (doseq [t to-trash]
                                 (trash state :contestant t {:unpreventable true}))
                               (doseq [h to-hq]
                                 (move state :contestant h :hand))
                               (if (not-empty remaining)
                                 (continue-ability state :contestant (reorder-choice :contestant (vec remaining)) card nil)
                                 (do (clear-wait-prompt state :challenger)
                                     (effect-completed state :contestant eid)))
                               (system-msg state :contestant (str "uses Bacterial Resourceming to add " (count to-hq)
                                                            " cards to HQ, discard " (count to-trash)
                                                            ", and arrange the top cards of R&D")))
                             (continue-ability state :contestant (hq-step
                                                             (clojure.set/difference (set remaining) (set [target]))
                                                             to-trash
                                                             (conj to-hq target)) card nil)))})
           (trash-step [remaining to-trash]
             {:async true
              :prompt "Select a card to discard"
              :choices (conj (vec remaining) "Done")
              :effect (req (if (= "Done" target)
                             (continue-ability state :contestant (hq-step remaining to-trash `()) card nil)
                             (continue-ability state :contestant (trash-step
                                                             (clojure.set/difference (set remaining) (set [target]))
                                                             (conj to-trash target)) card nil)))})]
     (let [arrange-rd (effect (continue-ability
                                {:optional
                                 {:async true
                                  :prompt "Arrange top 7 cards of R&D?"
                                  :yes-ability {:async true
                                                :effect (req (let [c (take 7 (:deck contestant))]
                                                               (when (:run @state)
                                                                (swap! state assoc-in [:run :shuffled-during-access :rd] true))
                                                               (show-wait-prompt state :challenger "Contestant to use Bacterial Resourceming")
                                                               (continue-ability state :contestant (trash-step c `()) card nil)))}}}
                                card nil))]
       {:effect arrange-rd
        :async true
        :stolen {:async true
                 :effect arrange-rd}
        :interactive (req true)}))

   "Better Citizen Resource"
   (letfn [(ability [kind]
             (effect (show-wait-prompt :challenger "Contestant to use Better Citizen Resource")
                     (continue-ability
                       :contestant
                       {:optional
                        {:prompt "Give the challenger 1 tag?"
                         :yes-ability {:async true
                                       :msg (str "give the Challenger a tag for " kind)
                                       :effect (req (swap! state assoc-in [:per-turn (:cid card)] true)
                                                    (tag-challenger state :challenger eid 1))}
                         :end-effect (effect (clear-wait-prompt :challenger))}}
                       card nil)))]
    {:events {:play-event {:req (req (and (first-event? state :challenger :run)
                                          (has-subtype? target "Run")
                                          (not (used-this-turn? (:cid card) state))))
                           :async true
                           :effect (ability "playing a run event")}
              :challenger-install {:silent (req true)
                               :req (req (and (has-subtype? target "Icebreaker")
                                              (first-event? state :challenger :challenger-install #(has-subtype? (first %) "Icebreaker"))
                                              (not (used-this-turn? (:cid card) state))))
                               :async true
                               :effect (ability "installing an characterbreaker")}}})

   "Bifrost Array"
   {:req (req (not (empty? (filter #(not= (:title %)
                                          "Bifrost Array")
                                   (:scored contestant)))))
    :optional {:prompt "Trigger the ability of a scored agenda?"
               :yes-ability {:prompt "Select an agenda to trigger the \"when scored\" ability of"
                             :choices {:req #(and (is-type? % "Agenda")
                                                  (not= (:title %)
                                                        "Bifrost Array")
                                                  (= (first (:zone %))
                                                     :scored)
                                                  (when-scored? %)
                                                  (:abilities %))}
                             :msg (msg "trigger the \"when scored\" ability of " (:title target))
                             :effect (effect (continue-ability (card-def target) target nil))}}}

   "Brain Rewiring"
   {:effect (effect (show-wait-prompt :challenger "Contestant to use Brain Rewiring")
                    (resolve-ability
                      {:optional
                       {:prompt "Pay credits to add random cards from Challenger's Grip to the bottom of their Stack?"
                        :yes-ability {:prompt "How many credits?"
                                      :choices {:number (req (min (:credit contestant)
                                                                  (count (:hand challenger))))}
                                      :effect (req (when (pos? target)
                                                     (pay state :contestant card :credit target)
                                                     (let [from (take target (shuffle (:hand challenger)))]
                                                       (doseq [c from]
                                                         (move state :challenger c :deck))
                                                       (system-msg state side (str "uses Brain Rewiring to pay " target
                                                                                   " [Credits] and add " target
                                                                                   " cards from the Challenger's Grip"
                                                                                   " to the bottom of their Stack."
                                                                                   " The Challenger draws 1 card"))
                                                       (draw state :challenger)
                                                       (clear-wait-prompt state :challenger))))}
                        :no-ability {:effect (effect (clear-wait-prompt :challenger))}}}
                     card nil))}

   "Braintrust"
   {:effect (effect (add-counter card :agenda (quot (- (get-counters card :advancement) 3) 2)))
    :silent (req true)
    :events {:pre-rez-cost {:req (req (character? target))
                            :effect (req (rez-cost-bonus state side (- (get-counters card :agenda))))}}}

   "Breaking News"
   {:async true
    :effect (effect (tag-challenger :challenger eid 2))
    :silent (req true)
    :msg "give the Challenger 2 tags"
    :end-turn {:effect (effect (lose :challenger :tag 2))
               :msg "make the Challenger lose 2 tags"}}

   "CFC Excavation Contract"
   {:effect (req (let [bios (count (filter #(has-subtype? % "Bioroid") (all-active-installed state :contestant)))
                       bucks (* bios 2)]
                   (gain-credits state side bucks)
                   (system-msg state side (str "gains " bucks " [Credits] from CFC Excavation Contract"))))}

   "Character Assassination"
   {:prompt "Select a muthereff to trash"
    :choices {:req #(and (installed? %)
                         (is-type? % "Muthereff"))}
    :msg (msg "trash " (:title target))
    :interactive (req true)
    :async true
    :effect (effect (trash eid target {:unpreventable true}))}

   "Chronos Project"
   {:msg "remove all cards in the Challenger's Heap from the game"
    :interactive (req true)
    :effect (effect (move-zone :challenger :discard :rfg))}

   "City Works Project"
   (letfn [(meat-damage [s c] (+ 2 (get-counters (get-card s c) :advancement)))]
     {:install-state :face-up
      :access {:req (req installed)
               :msg (msg "do " (meat-damage state card) " meat damage")
               :async true
               :effect (effect (damage eid :meat (meat-damage state card) {:card card}))}})

   "Clone Retirement"
   {:msg "remove 1 bad publicity" :effect (effect (lose :bad-publicity 1))
    :silent (req true)
    :stolen {:msg "force the Contestant to take 1 bad publicity"
             :effect (effect (gain-bad-publicity :contestant 1))}}

   "Contestantorate Sales Team"
   (let [e {:effect (req (when (pos? (get-counters card :credit))
                           (take-credits state :contestant 1)
                           (system-msg state :contestant (str "uses Contestantorate Sales Team to gain 1 [Credits]"))
                           (add-counter state side card :credit -1)))}]
     {:effect (effect (add-counter card :credit 10))
      :silent (req true)
      :events {:challenger-turn-begins e
               :contestant-turn-begins e}})

   "Contestantorate War"
   {:msg (msg (if (> (:credit contestant) 6) "gain 7 [Credits]" "lose all credits"))
    :interactive (req true)
    :effect (req (if (> (:credit contestant) 6)
                   (gain-credits state :contestant 7) (lose-credits state :contestant :all)))}

   "Crisis Management"
   (let [ability {:req (req tagged)
                  :async true
                  :label "Do 1 meat damage (start of turn)"
                  :once :per-turn
                  :msg "do 1 meat damage"
                  :effect (effect (damage eid :meat 1 {:card card}))}]
     {:events {:contestant-turn-begins ability}
      :abilities [ability]})

   "Dedicated Neural Net"
    (let [psi-effect
           {:async true
            :mandatory true
            :effect (req (if (not-empty (:hand contestant))
                           (do (show-wait-prompt state :challenger "Contestant to select cards in HQ to be accessed")
                               (continue-ability
                                 state :contestant
                                 {:prompt (msg "Select " (access-count state side :hq-access) " cards in HQ for the Challenger to access")
                                  :choices {:req #(and (in-hand? %) (card-is? % :side :contestant))
                                            :max (req (access-count state side :hq-access))}
                                  :effect (effect (clear-wait-prompt :challenger)
                                                  (continue-ability :challenger
                                                                    (access-helper-hq
                                                                      state (access-count state side :hq-access)
                                                                      ; access-helper-hq uses a set to keep track of which cards have already
                                                                      ; been accessed. Using the set difference we make the challenger unable to
                                                                      ; access non-selected cards from the contestant prompt
                                                                      (clojure.set/difference (set (:hand contestant)) (set targets)))
                                                                    card nil))}
                                 card nil))
                           (effect-completed state side eid)))}]
       {:events {:successful-run {:interactive (req true)
                                  :psi {:req (req (= target :hq))
                                        :once :per-turn
                                        :not-equal {:effect (req (when-not (:replace-access (get-in @state [:run :run-effect]))
                                                                   (swap! state update-in [:run :run-effect]
                                                                          #(assoc % :replace-access psi-effect)))
                                                                 (effect-completed state side eid))}}}}})

   "Degree Mill"
   {:steal-cost-bonus (req [:shuffle-installed-to-stack 2])}

   "Director Haas Pet Project"
   (letfn [(install-ability [server-name n]
             {:prompt "Select a card to install"
              :show-discard true
              :choices {:req #(and (= (:side %) "Contestant")
                                   (not (is-type? % "Operation"))
                                   (#{[:hand] [:discard]} (:zone %)))}
              :effect (req (contestant-install state side target server-name {:no-install-cost true})
                           (if (< n 2)
                             (continue-ability state side
                                               (install-ability (last (get-remote-names state)) (inc n))
                                               card nil)
                             (effect-completed state side eid)))
              :msg (msg (if (pos? n)
                          (contestant-install-msg target)
                          "create a new remote server, installing cards from HQ or Archives, ignoring all install costs"))})]
     {:optional {:prompt "Install cards in a new remote server?"
                 :yes-ability (install-ability "New remote" 0)}})

   "Domestic Sleepers"
   {:agendapoints-challenger (req 0)
    :abilities [{:cost [:click 3] :msg "place 1 agenda counter on Domestic Sleepers"
                 :req (req (not (:counter card)))
                 :effect (effect (gain-agenda-point 1)
                                 (set-prop card :counter {:agenda 1} :agendapoints 1))}]}

   "Eden Fragment"
   {:events {:pre-contestant-install
               {:req (req (and (is-type? target "Character")
                               (empty? (let [cards (map first (turn-events state side :contestant-install))]
                                         (filter #(is-type? % "Character") cards)))))
                :effect (effect (ignore-install-cost true))}
             :contestant-install
               {:req (req (and (is-type? target "Character")
                               (empty? (let [cards (map first (turn-events state side :contestant-install))]
                                         (filter #(is-type? % "Character") cards)))))
                :msg (msg "ignore the install cost of the first Character this turn")}}}

   "Efficiency Committee"
   {:silent (req true)
    :effect (effect (add-counter card :agenda 3))
    :abilities [{:cost [:click 1] :counter-cost [:agenda 1]
                 :effect (effect (gain :click 2)
                                 (register-turn-flag!
                                   card :can-advance
                                   (fn [state side card]
                                     ((constantly false)
                                       (toast state :contestant "Cannot advance cards this turn due to Efficiency Committee." "warning")))))
                 :msg "gain [Click][Click]"}]}

   "Elective Region"
   {:silent (req true)
    :effect (effect (add-counter card :agenda 2))
    :abilities [{:cost [:click 1]
                 :counter-cost [:agenda 1]
                 :once :per-turn
                 :effect (effect (gain :click 2))
                 :msg "gain [Click][Click]"}]}

   "Encrypted Portals"
   (character-boost-agenda "Code Gate")

   "Escalate Vitriol"
   {:abilities [{:label "Gain 1 [Credit] for each Challenger tag"
                 :cost [:click 1]
                 :once :per-turn
                 :msg (msg "gain " (:tag challenger) " [Credits]")
                 :effect (effect (gain-credits (:tag challenger)))}]}

   "Executive Retreat"
   {:effect (effect (add-counter card :agenda 1)
                    (shuffle-into-deck :hand))
    :interactive (req true)
    :abilities [{:cost [:click 1]
                 :counter-cost [:agenda 1]
                 :msg "draw 5 cards"
                 :effect (effect (draw 5))}]}

   "Explode-a-palooza"
   {:flags {:rd-reveal (req true)}
    :access {:async true
             :effect (effect (show-wait-prompt :challenger "Contestant to use Explode-a-palooza")
                             (continue-ability
                               {:optional {:prompt "Gain 5 [Credits] with Explode-a-palooza ability?"
                                           :yes-ability {:msg "gain 5 [Credits]"
                                                         :effect (effect (gain-credits :contestant 5)
                                                                         (clear-wait-prompt :challenger))}
                                           :no-ability {:effect (effect (clear-wait-prompt :challenger))}}}
                               card nil))}}

   "False Lead"
   {:abilities [{:req (req (>= (:click challenger) 2))
                 :msg "force the Challenger to lose [Click][Click]"
                 :effect (effect (forfeit card)
                                 (lose :challenger :click 2))}]}

   "Fetal AI"
   {:flags {:rd-reveal (req true)}
    :access {:async true
             :req (req (not= (first (:zone card)) :discard)) :msg "do 2 net damage"
             :effect (effect (damage eid :net 2 {:card card}))}
    :steal-cost-bonus (req [:credit 2])}

   "Firmware Updates"
   {:silent (req true)
    :effect (effect (add-counter card :agenda 3))
    :abilities [{:counter-cost [:agenda 1]
                 :choices {:req #(and (character? %)
                                      (can-be-advanced? %))}
                 :req (req (pos? (get-counters card :agenda)))
                 :msg (msg "place 1 advancement token on " (card-str state target))
                 :once :per-turn
                 :effect (effect (add-prop target :advance-counter 1))}]}

   "Genetic Resequencing"
   {:choices {:req #(= (last (:zone %)) :scored)}
    :msg (msg "add 1 agenda counter on " (:title target))
    :effect (effect (add-counter target :agenda 1))
    :silent (req true)}

   "Geothermal Fracking"
   {:effect (effect (add-counter card :agenda 2))
    :silent (req true)
    :abilities [{:cost [:click 1]
                 :counter-cost [:agenda 1]
                 :msg "gain 7 [Credits] and take 1 bad publicity"
                 :effect (effect (gain-credits 7)
                                 (gain-bad-publicity :contestant 1))}]}

   "Gila Hands Arcology"
   {:abilities [{:cost [:click 2]
                 :msg "gain 3 [Credits]"
                 :effect (effect (gain-credits 3))}]}

   "Global Food Initiative"
   {:agendapoints-challenger (req 2)}

   "Glenn Station"
   {:implementation "Doesn't prohibit hosting multiple cards"
    :abilities [{:label "Host a card from HQ on Glenn Station"
                 :cost [:click 1]
                 :msg "host a card from HQ"
                 :prompt "Choose a card to host on Glenn Station"
                 :choices (req (:hand contestant))
                 :effect (effect (host card target {:facedown true}))}
                {:label "Add a card on Glenn Station to HQ"
                 :cost [:click 1]
                 :msg "add a hosted card to HQ"
                 :prompt "Choose a card on Glenn Station"
                 :choices (req (:hosted card))
                 :effect (effect (move target :hand))}]}

   "Government Contracts"
   {:abilities [{:cost [:click 2]
                 :effect (effect (gain-credits 4))
                 :msg "gain 4 [Credits]"}]}

   "Government Takeover"
   {:abilities [{:cost [:click 1]
                 :effect (effect (gain-credits 3))
                 :msg "gain 3 [Credits]"}]}

   "Graft"
   (letfn [(graft [n] {:prompt "Choose a card to add to HQ with Graft"
                       :async true
                       :choices (req (cancellable (:deck contestant) :sorted))
                       :msg (msg "add " (:title target) " to HQ from R&D")
                       :cancel-effect (req (shuffle! state side :deck)
                                           (system-msg state side (str "shuffles R&D"))
                                           (effect-completed state side eid))
                       :effect (req (move state side target :hand)
                                    (if (< n 3)
                                      (continue-ability state side (graft (inc n)) card nil)
                                      (do (shuffle! state side :deck)
                                          (system-msg state side (str "shuffles R&D"))
                                          (effect-completed state side eid))))})]
     {:async true
      :msg "add up to 3 cards from R&D to HQ"
      :effect (effect (continue-ability (graft 1) card nil))})

   "Hades Fragment"
   {:flags {:contestant-phase-12 (req (and (not-empty (get-in @state [:contestant :discard]))
                                     (is-scored? state :contestant card)))}
    :abilities [{:prompt "Select a card to add to the bottom of R&D"
                 :show-discard true
                 :choices {:req #(and (= (:side %) "Contestant")
                                      (= (:zone %) [:discard]))}
                 :effect (effect (move target :deck))
                 :msg (msg "add "
                           (if (:seen target)
                             (:title target)
                             "a card")
                           " to the bottom of R&D")}]}

   "Helium-3 Deposit"
   {:async true
    :interactive (req true)
    :prompt "How many power counters?"
    :choices ["0" "1" "2"]
    :effect (req (let [c (str->int target)]
                   (continue-ability
                     state side
                     {:choices {:req #(pos? (get-counters % :power))}
                      :msg (msg "add " c " power counters on " (:title target))
                      :effect (effect (add-counter target :power c))}
                     card nil)))}

   "High-Risk Investment"
   {:effect (effect (add-counter card :agenda 1))
    :silent (req true)
    :abilities [{:cost [:click 1]
                 :counter-cost [:agenda 1]
                 :msg (msg "gain " (:credit challenger) " [Credits]")
                 :effect (effect (gain-credits (:credit challenger)))}]}

   "Hostile Takeover"
   {:msg "gain 7 [Credits] and take 1 bad publicity"
    :effect (effect (gain-credits 7)
                    (gain-bad-publicity :contestant 1))
    :interactive (req true)}

   "Hollywood Renovation"
   {:install-state :face-up
    :events {:advance
             {:async true
              :req (req (= (:cid card)
                           (:cid target)))
              :effect (req (let [n (if (>= (get-counters (get-card state card) :advancement) 6) 2 1)]
                             (continue-ability state side
                              {:choices {:req #(and (not= (:cid %)
                                                          (:cid card))
                                                    (can-be-advanced? %))}
                               :msg (msg "place " n
                                         " advancement tokens on "
                                         (card-str state target))
                               :effect (effect (add-prop :contestant target :advance-counter n {:placed true}))}
                              card nil)))}}}

   "House of Knives"
   {:effect (effect (add-counter card :agenda 3))
    :silent (req true)
    :abilities [{:counter-cost [:agenda 1]
                 :msg "do 1 net damage"
                 :req (req (:run @state))
                 :once :per-run
                 :effect (effect (damage eid :net 1 {:card card}))}]}

   "Ikawah Project"
   {:steal-cost-bonus (req [:credit 2 :click 1])}

   "Illicit Sales"
   {:async true
    :effect (req (wait-for (resolve-ability
                             state side
                             {:optional
                              {:prompt "Take 1 bad publicity from Illicit Sales?"
                               :yes-ability {:msg "take 1 bad publicity"
                                             :effect (effect (gain-bad-publicity :contestant 1))}}}
                             card nil)
                           (do (let [n (* 3 (+ (get-in @state [:contestant :bad-publicity]) (:has-bad-pub contestant)))]
                                 (gain-credits state side n)
                                 (system-msg state side (str "gains " n " [Credits] from Illicit Sales"))
                                 (effect-completed state side eid)))))}

   "Improved Protein Source"
   {:msg "make the Challenger gain 4 [Credits]"
    :effect (effect (gain-credits :challenger 4))
    :interactive (req true)
    :stolen {:msg "make the Challenger gain 4 [Credits]"
             :effect (effect (gain-credits :challenger 4))}}

   "Improved Tracers"
   {:silent (req true)
    :effect (req (update-all-character state side))
    :swapped {:effect (req (update-all-character state side))}
    :events {:pre-character-strength {:req (req (has-subtype? target "Tracer"))
                                :effect (effect (character-strength-bonus 1 target))}
             :pre-init-trace {:req (req (has-subtype? target "Tracer"))
                              :effect (effect (init-trace-bonus 1))}}}

   "Labyrinthine Servers"
   {:interactions {:prevent [{:type #{:jack-out}
                              :req (req (pos? (get-counters card :power)))}]}
    :silent (req true)
    :effect (effect (add-counter card :power 2))
    :abilities [{:req (req (:run @state))
                 :counter-cost [:power 1]
                 :effect (req (let [ls (filter #(= "Labyrinthine Servers" (:title %)) (:scored contestant))]
                                (jack-out-prevent state side)
                                (when (zero? (reduce + (for [c ls] (get-counters c :power))))
                                  (swap! state update-in [:prevent] dissoc :jack-out))))
                 :msg "prevent the Challenger from jacking out"}]}

   "Lcharacternse Acquisition"
   {:interactive (req true)
    :prompt "Select an site or region to install from Archives or HQ"
    :show-discard true
    :choices {:req #(and (#{"Site" "Region"} (:type %))
                         (#{[:hand] [:discard]} (:zone %))
                         (= (:side %) "Contestant"))}
    :msg (msg "install and rez " (:title target) ", ignoring all costs")
    :effect (effect (contestant-install eid target nil {:install-state :rezzed-no-cost}))}

   "Mandatory Seed Replacement"
   (letfn [(msr [] {:prompt "Select two pieces of Character to swap positions"
                    :choices {:req #(and (installed? %)
                                         (character? %))
                              :max 2}
                    :async true
                    :effect (req (if (= (count targets) 2)
                                   (do (swap-character state side (first targets) (second targets))
                                       (system-msg state side
                                                   (str "swaps the position of "
                                                        (card-str state (first targets))
                                                        " and "
                                                        (card-str state (second targets))))
                                       (continue-ability state side (msr) card nil))
                                   (do (system-msg state :contestant (str "has finished rearranging Character"))
                                       (effect-completed state side eid))))})]
     {:async true
      :msg "rearrange any number of Character"
      :effect (effect (continue-ability (msr) card nil))})

   "Mandatory Regions"
   {:msg "gain an additional [Click] per turn"
    :silent (req true)
    :effect (req (gain state :contestant
                       :click 1
                       :click-per-turn 1))
    :swapped {:msg "gain an additional [Click] per turn"
              :effect (req (when (= (:active-player @state) :contestant)
                             (gain state :contestant :click 1))
                           (gain state :contestant :click-per-turn 1))}
    :leave-play (req (lose state :contestant
                           :click 1
                           :click-per-turn 1))}

   "Market Research"
   {:interactive (req true)
    :req (req tagged)
    :effect (effect (add-counter card :agenda 1)
                    (set-prop card :agendapoints 3))}

   "Medical Breakthrough"
   {:silent (req true)
    :effect (effect (update-all-advancement-costs))
    :stolen {:effect (effect (update-all-advancement-costs))}
    :advancement-cost-bonus (req (- (count (filter #(= (:title %) "Medical Breakthrough")
                                                   (concat (:scored contestant) (:scored challenger))))))}

   "Merger"
   {:agendapoints-challenger (req 3)}

   "Meteor Mining"
   {:interactive (req true)
    :async true
    :prompt "Use Meteor Mining?"
    :choices (req (if (< (:tag challenger) 2)
                    ["Gain 7 [Credits]" "No action"]
                    ["Gain 7 [Credits]" "Do 7 meat damage" "No action"]))
    :effect (req (case target
                   "Gain 7 [Credits]"
                   (do (gain-credits state side 7)
                       (system-msg state side "uses Meteor Mining to gain 7 [Credits]")
                       (effect-completed state side eid))
                   "Do 7 meat damage"
                   (do (damage state side eid :meat 7 {:card card})
                       (system-msg state side "uses Meteor Mining do 7 meat damage"))
                   "No action"
                   (do (system-msg state side "does not use Meteor Mining")
                       (effect-completed state side eid))))}

   "NAPD Contract"
   {:steal-cost-bonus (req [:credit 4])
    :advancement-cost-bonus (req (+ (:bad-publicity contestant)
                                    (:has-bad-pub contestant)))}

   "New Construction"
   {:install-state :face-up
    :events {:advance
             {:optional
              {:req (req (= (:cid card) (:cid target)))
               :prompt "Install a card from HQ in a new remote?"
               :yes-ability {:prompt "Select a card to install"
                             :choices {:req #(and (not (is-type? % "Operation"))
                                                  (not (is-type? % "Character"))
                                                  (= (:side %) "Contestant")
                                                  (in-hand? %))}
                             :msg (msg "install a card from HQ"
                                       (when (>= (get-counters (get-card state card) :advancement) 5)
                                         " and rez it, ignoring all costs"))
                             :effect (req (if (>= (get-counters (get-card state card) :advancement) 5)
                                            (do (contestant-install state side target "New remote"
                                                              {:install-state :rezzed-no-cost})
                                                (trigger-event state side :rez target))
                                            (contestant-install state side target "New remote")))}}}}}

   "Net Quarantine"
   (let [nq {:effect (req (let [extra (int (/ (:challenger-spent target) 2))]
                            (when (pos? extra)
                              (gain-credits state side extra)
                              (system-msg state :contestant (str "uses Net Quarantine to gain " extra " [Credits]")))
                            (when (some? (get-in @state [:challenger :temp-link]))
                              (swap! state assoc-in [:challenger :link] (:temp-link challenger))
                              (swap! state dissoc-in [:challenger :temp-link]))))}]
   {:events {:trace {:once :per-turn
                     :silent (req true)
                     :effect (req (system-msg state :contestant "uses Net Quarantine to reduce Challenger's base link to zero")
                                  (swap! state assoc-in [:challenger :temp-link] (:link challenger))
                                  (swap! state assoc-in [:challenger :link] 0))}
             :successful-trace nq
             :unsuccessful-trace nq}})

   "NEXT Wave 2"
   {:not-when-scored true
    :req (req (some #(and (rezzed? %)
                          (character? %)
                          (has-subtype? % "NEXT"))
                    (all-installed state :contestant)))
    :optional {:prompt "Do 1 brain damage with NEXT Wave 2?"
               :yes-ability {:msg "do 1 brain damage"
                             :effect (effect (damage eid :brain 1 {:card card}))}}}

   "Nisei MK II"
   {:silent (req true)
    :effect (effect (add-counter card :agenda 1))
    :abilities [{:req (req (:run @state))
                 :counter-cost [:agenda 1]
                 :msg "end the run"
                 :effect (effect (end-run))}]}

   "Oaktown Renovation"
   {:install-state :face-up
    :events {:advance {:req (req (= (:cid card) (:cid target)))
                       :msg (msg "gain " (if (>= (get-counters (get-card state card) :advancement) 5) "3" "2") " [Credits]")
                       :effect (req (gain-credits state side
                                          (if (>= (get-counters (get-card state card) :advancement) 5) 3 2)))}}}

   "Obokata Protocol"
   {:steal-cost-bonus (req [:net-damage 4])}

   "Paper Trail"
   {:trace {:base 6
            :successful {:msg "trash all connection and job muthereffs"
                         :effect (req (doseq [muthereff (filter #(or (has-subtype? % "Job")
                                                                    (has-subtype? % "Connection"))
                                                               (all-active-installed state :challenger))]
                                        (trash state side muthereff)))}}}

   "Personality Profiles"
   (let [pp {:req (req (pos? (count (:hand challenger))))
             :effect (effect (trash (first (shuffle (:hand challenger)))))
             :msg (msg "force the Challenger to trash " (:title (last (:discard challenger))) " from their Grip at random")}]
     {:events {:searched-stack pp
               :challenger-install (assoc pp :req (req (and (some #{:discard} (:previous-zone target))
                                                        (pos? (count (:hand challenger))))))}})

   "Philotic Entanglement"
   {:interactive (req true)
    :req (req (pos? (count (:scored challenger))))
    :msg (msg "do " (count (:scored challenger)) " net damage")
    :effect (effect (damage eid :net (count (:scored challenger)) {:card card}))}

   "Posted Bounty"
   {:optional {:prompt "Forfeit Posted Bounty to give the Challenger 1 tag and take 1 bad publicity?"
               :yes-ability {:msg "give the Challenger 1 tag and take 1 bad publicity"
                             :async true
                             :effect (effect (gain-bad-publicity :contestant eid 1)
                                             (tag-challenger :challenger eid 1)
                                             (forfeit card))}}}

   "Priority Requisition"
   {:interactive (req true)
    :choices {:req #(and (character? %)
                         (not (rezzed? %))
                         (installed? %))}
    :effect (effect (rez target {:ignore-cost :all-costs}))}

   "Private Security Force"
   {:abilities [{:req (req tagged)
                 :cost [:click 1]
                 :effect (effect (damage eid :meat 1 {:card card}))
                 :msg "do 1 meat damage"}]}

   "Profiteering"
   {:interactive (req true)
    :choices ["0" "1" "2" "3"] :prompt "How many bad publicity?"
    :msg (msg "take " target " bad publicity and gain " (* 5 (str->int target)) " [Credits]")
    :effect (req (let [bp (:bad-publicity (:contestant @state))]
                   (gain-bad-publicity state :contestant eid (str->int target))
                   (if (< bp (:bad-publicity (:contestant @state)))
                     (gain-credits state :contestant (* 5 (str->int target))))))}

   "Project Ares"
   (letfn [(trash-count-str [card]
             (quantify (- (get-counters card :advancement) 4) "installed card"))]
     {:silent (req true)
      :req (req (and (> (get-counters card :advancement) 4)
                     (pos? (count (all-installed state :challenger)))))
      :msg (msg "force the Challenger to trash " (trash-count-str card) " and take 1 bad publicity")
      :async true
      :effect (effect (show-wait-prompt :contestant "Challenger to trash installed cards")
                      (continue-ability
                       :challenger
                       {:prompt (msg "Select " (trash-count-str card) " installed cards to trash")
                        :choices {:max (min (- (get-counters card :advancement) 4)
                                            (count (all-installed state :challenger)))
                                  :req #(and (= (:side %) "Challenger")
                                             (:installed %))}
                        :effect (effect (trash-cards targets)
                                        (system-msg (str "trashes " (join ", " (map :title targets))))
                                        (gain-bad-publicity :contestant 1))}
                       card nil)
                      (clear-wait-prompt :contestant))})

   "Project Atlas"
   {:silent (req true)
    :effect (effect (add-counter card :agenda (max 0 (- (get-counters card :advancement) 3))))
    :abilities [{:counter-cost [:agenda 1]
                 :prompt "Choose a card"
                 :label "Search R&D and add 1 card to HQ"
                 ;; we need the req or the prompt will still show
                 :req (req (pos? (get-counters card :agenda)))
                 :msg (msg "add " (:title target) " to HQ from R&D")
                 :choices (req (cancellable (:deck contestant) :sorted))
                 :cancel-effect (effect (system-msg "cancels the effect of Project Atlas"))
                 :effect (effect (shuffle! :deck)
                                 (move target :hand))}]}

   "Project Beale"
   {:interactive (req true)
    :agendapoints-challenger (req 2)
    :effect (req (let [n (quot (- (get-counters card :advancement) 3) 2)]
                    (set-prop state side card
                              :counter {:agenda n}
                              :agendapoints (+ 2 n))))}

   "Project Kusanagi"
   {:silent (req true)
    :effect (effect (add-counter card :agenda (- (get-counters card :advancement) 2)))
    :abilities [{:counter-cost [:agenda 1]
                 :msg "make a piece of Character gain \"[Subroutine] Do 1 net damage\" after all its other subroutines for the remainder of the run"}]}

   "Project Vitruvius"
   {:silent (req true)
    :effect (effect (add-counter card :agenda (- (get-counters card :advancement) 3)))
    :abilities [{:counter-cost [:agenda 1]
                 :prompt "Choose a card in Archives to add to HQ"
                 :show-discard true
                 :choices {:req #(and (in-discard? %)
                                      (= (:side %) "Contestant"))}
                 :req (req (pos? (get-counters card :agenda)))
                 :msg (msg "add "
                           (if (:seen target)
                             (:title target) "an unseen card ")
                           " to HQ from Archives")
                 :effect (effect (move target :hand))}]}

   "Project Wotan"
   {:silent (req true)
    :effect (effect (add-counter card :agenda 3))
    :abilities [{:req (req (and (character? current-character)
                                (rezzed? current-character)
                                (has-subtype? current-character "Bioroid")))
                 :counter-cost [:agenda 1]
                 :msg (str "make the approached piece of Bioroid Character gain \"[Subroutine] End the run\""
                           "after all its other subroutines for the remainder of this run")}]}

   "Puppet Master"
   {:events {:successful-run
             {:interactive (req true)
              :async true
              :effect (req (show-wait-prompt state :challenger "Contestant to use Puppet Master")
                           (continue-ability
                             state :contestant
                             {:prompt "Select a card to place 1 advancement token on"
                              :player :contestant
                              :choices {:req can-be-advanced?}
                              :cancel-effect (effect (clear-wait-prompt :challenger)
                                                     (effect-completed eid))
                              :msg (msg "place 1 advancement token on " (card-str state target))
                              :effect (effect (add-prop :contestant target :advance-counter 1 {:placed true})
                                              (clear-wait-prompt :challenger))} card nil))}}}

   "Quantum Predictive Model"
   {:flags {:rd-reveal (req true)}
    :access {:req (req tagged)
             :async true
             :interactive (req true)
             :effect (req (wait-for (as-agenda state side card 1)
                                    (continue-ability
                                      state :challenger
                                      {:prompt "Quantum Predictive Model was added to the contestant's score area"
                                       :choices ["OK"]}
                                      card nil)))
             :msg "add it to their score area and gain 1 agenda point"}}

   "Rebranding Team"
   (letfn [(get-sites [state contestant]
             (filter #(is-type? % "Site") (concat (all-installed state :contestant)
                                                   (:deck contestant)
                                                   (:hand contestant)
                                                   (:discard contestant))))
           (add-ad [state side c]
             (update! state side (assoc-in c [:persistent :subtype] "Advertisement")))]
     {:interactive (req true)
      :msg "make all sites gain Advertisement"
      :effect (req (doseq [c (get-sites state contestant)] (add-ad state side c)))
      :swapped {:msg "make all sites gain Advertisement"
                :effect (req (doseq [c (get-sites state contestant)] (add-ad state side c)))}
      :leave-play (req (doseq [c (get-sites state contestant)]
                         (update! state side (assoc-in c [:persistent :subtype]
                                                      (->> (split (or (-> c :persistent :subtype) "") #" - ")
                                                           (drop 1) ;so that all actual ads remain ads if agenda leaves play
                                                           (join " - "))))))})

   "Reeducation"
   (letfn [(contestant-final [chosen original]
             {:prompt (str "The bottom cards of R&D will be " (clojure.string/join  ", " (map :title chosen)) ".")
              :choices ["Done" "Start over"]
              :async true
              :msg (req (let [n (count chosen)]
                          (str "add " n " cards from HQ to the bottom of R&D and draw " n " cards.
                          The Challenger randomly adds " (if (<= n (count (:hand challenger))) n 0) " cards from their Grip
                          to the bottom of the Stack")))
              :effect (req (let [n (count chosen)]
                             (if (= target "Done")
                             (do (doseq [c (reverse chosen)] (move state :contestant c :deck))
                                 (draw state :contestant n)
                                 ; if contestant chooses more cards than challenger's hand, don't shuffle challenger hand back into Stack
                                 (when (<= n (count (:hand challenger)))
                                   (doseq [r (take n (shuffle (:hand challenger)))] (move state :challenger r :deck)))
                                 (clear-wait-prompt state :challenger)
                                 (effect-completed state side eid))
                             (continue-ability state side (contestant-choice original '() original) card nil))))})
           (contestant-choice [remaining chosen original] ; Contestant chooses cards until they press 'Done'
             {:prompt "Choose a card to move to bottom of R&D"
              :choices (conj (vec remaining) "Done")
              :async true
              :effect (req (let [chosen (cons target chosen)]
                             (if (not= target "Done")
                               (continue-ability
                                 state side
                                 (contestant-choice (remove-once #(= target %) remaining) chosen original)
                                 card nil)
                               (if (pos? (count (remove #(= % "Done") chosen)))
                                 (continue-ability state side (contestant-final (remove #(= % "Done") chosen) original) card nil)
                                 (do (system-msg state side "does not add any cards from HQ to bottom of R&D")
                                     (clear-wait-prompt state :challenger)
                                     (effect-completed state side eid))))))})]
   {:async true
    :effect (req (show-wait-prompt state :challenger "Contestant to add cards from HQ to bottom of R&D")
                 (let [from (get-in @state [:contestant :hand])]
                   (if (pos? (count from))
                     (continue-ability state :contestant (contestant-choice from '() from) card nil)
                     (do (system-msg state side "does not add any cards from HQ to bottom of R&D")
                         (effect-completed state side eid)))))})

   "Remote Data Farm"
   {:silent (req true)
    :msg "increase their maximum hand size by 2"
    :effect (effect (gain :hand-size 2))
    :swapped {:msg "increase their maximum hand size by 2"
              :effect (effect (gain :hand-size 2))}
    :leave-play (effect (lose :hand-size 2))}

   "Remote Enforcement"
   {:interactive (req true)
    :optional {:prompt "Search R&D for a piece of character to install protecting a remote server?"
               :yes-ability {:async true
                             :prompt "Choose a piece of character"
                             :choices (req (filter character? (:deck contestant)))
                             :effect (req (let [chosen-character target]
                                            (continue-ability state side
                                              {:async true
                                               :prompt (str "Select a server to install " (:title chosen-character) " on")
                                               :choices (filter #(not (#{"HQ" "Archives" "R&D"} %))
                                                                (contestant-install-list state chosen-character))
                                               :effect (effect (shuffle! :deck)
                                                               (contestant-install eid chosen-character target {:install-state :rezzed-no-rez-cost}))}
                                              card nil)))}}}

   "Research Grant"
   {:interactive (req true)
    :silent (req (empty? (filter #(= (:title %) "Research Grant") (all-installed state :contestant))))
    :req (req (not (empty? (filter #(= (:title %) "Research Grant") (all-installed state :contestant)))))
    :async true
    :effect (effect (continue-ability
                      {:prompt "Select another installed copy of Research Grant to score"
                       :choices {:req #(= (:title %) "Research Grant")}
                       :async true
                       :effect (effect (set-prop target :advance-counter (:advancementcost target))
                                       (score eid (get-card state target)))
                       :msg "score another installed copy of Research Grant"}
                     card nil))}

   "Restructured Datapool"
   {:abilities [{:cost [:click 1]
                 :trace {:base 2
                         :successful {:msg "give the Challenger 1 tag"
                                      :async true
                                      :effect (effect (tag-challenger :challenger eid 1))}}}]}

   "Self-Destruct Chips"
   {:silent (req true)
    :msg "decrease the Challenger's maximum hand size by 1"
    :effect (effect (lose :challenger :hand-size 1))
    :swapped {:msg "decrease the Challenger's maximum hand size by 1"
              :effect (effect (lose :challenger :hand-size 1))}
    :leave-play (effect (gain :challenger :hand-size 1))}

   "Sensor Net Activation"
   {:effect (effect (add-counter card :agenda 1))
    :silent (req true)
    :abilities [{:counter-cost [:agenda 1]
                 :req (req (some #(and (has-subtype? % "Bioroid") (not (rezzed? %))) (all-installed state :contestant)))
                 :prompt "Choose a bioroid to rez, ignoring all costs"
                 :choices {:req #(and (has-subtype? % "Bioroid") (not (rezzed? %)))}
                 :msg (msg "rez " (card-str state target) ", ignoring all costs")
                 :effect (req (let [c target]
                                (rez state side c {:ignore-cost :all-costs})
                                (register-events state side
                                  {:contestant-turn-ends {:effect (effect (derez c)
                                                                    (unregister-events card))}
                                   :challenger-turn-ends {:effect (effect (derez c)
                                                                      (unregister-events card))}} card)))}]
      :events {:contestant-turn-ends nil :challenger-turn-ends nil}}

   "Sentinel Defense Resource"
   {:events {:pre-resolve-damage {:req (req (and (= target :brain)
                                                 (pos? (last targets))))
                                  :msg "do 1 net damage"
                                  :effect (effect (damage eid :net 1 {:card card}))}}}

   "Show of Force"
   {:async true
    :msg "do 2 meat damage"
    :effect (effect (damage eid :meat 2 {:card card}))}

   "SSL Endorsement"
   (let [add-credits (effect (add-counter card :credit 9))
         remove-credits {:optional {:req (req (pos? (get-counters card :credit)))
                                    :prompt "Gain 3 [Credits] from SSL Endorsement?"
                                    :yes-ability
                                    {:effect (req (when (pos? (get-counters card :credit))
                                                    (take-credits state :contestant 3)
                                                    (system-msg state :contestant (str "uses SSL Endorsement to gain 3 [Credits]"))
                                                    (add-counter state side card :credit -3)))}}}]
     {:effect add-credits
      :stolen {:effect add-credits}
      :interactive (req true)
      :events {:contestant-turn-begins remove-credits}
      :flags {:has-events-when-stolen true}})

   "Standoff"
   (letfn [(stand [side]
             {:async true
              :prompt "Choose one of your installed cards to trash due to Standoff"
              :choices {:req #(and (installed? %)
                                   (same-side? side (:side %)))}
              :cancel-effect (req (if (= side :challenger)
                                    (do (draw state :contestant)
                                        (gain-credits state :contestant 5)
                                        (clear-wait-prompt state :contestant)
                                        (system-msg state :challenger "declines to trash a card due to Standoff")
                                        (system-msg state :contestant "draws a card and gains 5 [Credits] from Standoff")
                                        (effect-completed state :contestant eid))
                                    (do (system-msg state :contestant "declines to trash a card from Standoff")
                                        (clear-wait-prompt state :challenger)
                                        (effect-completed state :contestant eid))))
              :effect (req (wait-for (trash state side target {:unpreventable true})
                                     (do
                                       (system-msg state side (str "trashes " (card-str state target) " due to Standoff"))
                                       (clear-wait-prompt state (other-side side))
                                       (show-wait-prompt state side (str (side-str (other-side side)) " to trash a card for Standoff"))
                                       (continue-ability state (other-side side) (stand (other-side side)) card nil))))})]
     {:interactive (req true)
      :async true
      :effect (effect (show-wait-prompt (str (side-str (other-side side)) " to trash a card for Standoff"))
                      (continue-ability :challenger (stand :challenger) card nil))})

   "Successful Field Test"
   (letfn [(sft [n max] {:prompt "Select a card in HQ to install with Successful Field Test"
                         :priority -1
                         :async true
                         :choices {:req #(and (= (:side %) "Contestant")
                                              (not (is-type? % "Operation"))
                                              (in-hand? %))}
                         :effect (req (wait-for
                                        (contestant-install state side target nil {:no-install-cost true})
                                        (if (< n max)
                                          (continue-ability state side (sft (inc n) max) card nil)
                                          (effect-completed state side eid))))})]
     {:async true
      :msg "install cards from HQ, ignoring all costs"
      :effect (req (let [max (count (filter #(not (is-type? % "Operation")) (:hand contestant)))]
                     (continue-ability state side (sft 1 max) card nil)))})

   "Superior Cyberwalls"
   (character-boost-agenda "Barrier")

   "TGTBT"
   {:flags {:rd-reveal (req true)}
    :access {:msg "give the Challenger 1 tag"
             :async true
             :effect (effect (tag-challenger :challenger eid 1))}}

   "The Cleaners"
   {:events {:pre-damage {:req (req (and (= target :meat)
                                         (= side :contestant)))
                          :msg "do 1 additional meat damage"
                          :effect (effect (damage-bonus :meat 1))}}}

   "The Future is Now"
   {:interactive (req true)
    :prompt "Choose a card to add to HQ"
    :choices (req (:deck contestant))
    :msg (msg "add a card from R&D to HQ and shuffle R&D")
    :req (req (pos? (count (:deck contestant))))
    :effect (effect (shuffle! :deck)
                    (move target :hand))}

   "The Future Perfect"
   {:flags {:rd-reveal (req true)}
    :access
    {:psi {:req (req (not installed))
           :not-equal {:msg (msg "prevent it from being stolen")
                       :effect (effect (register-run-flag! card :can-steal
                                                           (fn [_ _ c] (not= (:cid c) (:cid card))))
                                       ;; TODO: investigate why this is needed??
                                       (effect-completed eid))}}}}

   "Underway Renovation"
   (letfn [(adv4? [s c] (if (>= (get-counters (get-card s c) :advancement) 4) 2 1))]
     {:install-state :face-up
      :events {:advance {:req (req (= (:cid card) (:cid target)))
                         :msg (msg (if (pos? (count (:deck challenger)))
                                     (str "trash "
                                          (join ", " (map :title (take (adv4? state card) (:deck challenger))))
                                          " from the Challenger's stack")
                                     "trash from the Challenger's stack but it is empty"))
                         :effect (effect (mill :contestant :challenger (adv4? state card)))}}})

   "Unorthodox Predictions"
   {:async false
    :implementation "Prevention of subroutine breaking is not enforced"
    :prompt "Choose an Character type for Unorthodox Predictions"
    :choices ["Barrier" "Code Gate" "Sentry"]
    :msg (msg "prevent subroutines on " target " Character from being broken until next turn.")}

   "Utopia Fragment"
   {:events {:pre-steal-cost {:req (req (pos? (get-counters target :advancement)))
                              :effect (req (let [counter (get-counters target :advancement)]
                                             (steal-cost-bonus state side [:credit (* 2 counter)])))}}}

   "Vanity Project"
   {}

   "Veterans Resource"
   {:interactive (req true)
    :msg "lose 2 bad publicity"
    :effect (effect (lose :bad-publicity 2))}

   "Viral Weaponization"
   (let [dmg {:msg "do 1 net damage for each card in the grip"
              :async true
              :effect (req (let [cnt (count (:hand challenger))]
                             (unregister-events state side card)
                             (damage state side eid :net cnt {:card card})))}]
     {:effect (effect (register-events
                        {:contestant-turn-ends dmg
                         :challenger-turn-ends dmg}
                        card))
      :events {:contestant-turn-ends nil
               :challenger-turn-ends nil}})

   "Voting Machine Initiative"
   {:silent (req true)
    :effect (effect (add-counter card :agenda 3))
    :events {:challenger-turn-begins
             {:async true
              :req (req (pos? (get-counters card :agenda)))
              :effect (effect (show-wait-prompt :challenger "Contestant to use Voting Machine Initiative")
                              (continue-ability
                                {:optional
                                 {:player :contestant
                                  :prompt "Use Voting Machine Initiative to make the Challenger lose 1 [Click]?"
                                  :yes-ability {:msg "make the Challenger lose 1 [Click]"
                                                :effect (effect (lose :challenger :click 1)
                                                                (add-counter card :agenda -1)
                                                                (clear-wait-prompt :challenger))}
                                  :no-ability {:effect (effect (clear-wait-prompt :challenger))}}}
                                card nil))}}}

   "Vulcan Coverup"
   {:interactive (req true)
    :msg "do 2 meat damage"
    :effect (effect (damage eid :meat 2 {:card card}))
    :stolen {:msg "force the Contestant to take 1 bad publicity"
             :effect (effect (gain-bad-publicity :contestant 1))}}

   "Water Monopoly"
   {:events {:pre-install {:req (req (and (is-type? target "Muthereff")
                                          (not (has-subtype? target "Virtual"))
                                          (not (second targets)))) ; not facedown
                           :effect (effect (install-cost-bonus [:credit 1]))}}}})
