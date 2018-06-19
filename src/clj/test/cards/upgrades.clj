(ns test.cards.regions
  (:require [game.core :as core]
            [test.core :refer :all]
            [test.utils :refer :all]
            [test.macros :refer :all]
            [clojure.test :refer :all]))


(deftest amazon-industrial-zone
  ;; Amazon Industrial Zone - Immediately reveal Character installed over its server at 3 credit discount
  (do-game
    (new-game (default-contestant [(qty "Spiderweb" 1) (qty "Amazon Industrial Zone" 1)])
              (default-challenger))
    (take-credits state :contestant 1)
    (play-from-hand state :contestant "Amazon Industrial Zone" "New remote")
    (let [aiz (get-content state :remote1 0)]
      (core/reveal state :contestant aiz)
      (is (= 2 (:credit (get-contestant))))
      (play-from-hand state :contestant "Spiderweb" "Server 1")
      (prompt-choice :contestant "Yes") ; optional ability
      (let [spid (get-character state :remote1 0)]
        (is (get-in (refresh spid) [:revealed]) "Spiderweb revealed")
        (is (= 1 (:credit (get-contestant))) "Paid only 1 credit to reveal")))))

(deftest ben-musashi
  ;; Ben Musashi - pay 2 net damage to steal from this server
  (do-game
    (new-game (default-contestant [(qty "Ben Musashi" 1) (qty "House of Knives" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Ben Musashi" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)
    (let [bm (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (core/reveal state :contestant bm)
      (run-empty-server state "Server 1")
      ;; challenger now chooses which to access.
      (prompt-select :challenger hok)
      ;; prompt should be asking for the 2 net damage cost
      (is (= "House of Knives" (:title (:card (first (:prompt (get-challenger))))))
          "Prompt to pay 2 net damage")
      (prompt-choice :challenger "No")
      (is (= 5 (:credit (get-challenger))) "Challenger did not pay 2 net damage")
      (is (= 0 (count (:scored (get-challenger)))) "No scored agendas")
      (prompt-select :challenger bm)
      (prompt-choice :challenger "No")
      (run-empty-server state "Server 1")
      (prompt-select :challenger hok)
      (prompt-choice :challenger "Yes")
      (is (= 2 (count (:discard (get-challenger)))) "Challenger took 2 net")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest ben-musashi-rd
  ;; Ben Musashi - on R&D access
  (do-game
    (new-game (default-contestant [(qty "Ben Musashi" 1) (qty "House of Knives" 1)])
              (default-challenger))
    (starting-hand state :contestant ["Ben Musashi"])
    (play-from-hand state :contestant "Ben Musashi" "R&D")
    (take-credits state :contestant)
    (let [bm (get-content state :rd 0)]
      (core/reveal state :contestant bm)
      (run-empty-server state "R&D")
      ;; challenger now chooses which to access.
      (prompt-choice :challenger "Card from deck")
      ;; prompt should be asking for the 2 net damage cost
      (is (= "House of Knives" (:title (:card (first (:prompt (get-challenger))))))
          "Prompt to pay 2 net damage")
      (prompt-choice :challenger "No")
      (is (= 5 (:credit (get-challenger))) "Challenger did not pay 2 net damage")
      (is (= 0 (count (:scored (get-challenger)))) "No scored agendas")
      (prompt-choice :challenger "Ben Musashi")
      (prompt-choice :challenger "No")
      (run-empty-server state "R&D")
      (prompt-choice :challenger "Card from deck")
      (prompt-choice :challenger "Yes")
      (is (= 2 (count (:discard (get-challenger)))) "Challenger took 2 net")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest ben-musashi-trash
  ;; Ben Musashi - pay even when trashed
  (do-game
    (new-game (default-contestant [(qty "Ben Musashi" 3) (qty "House of Knives" 3)])
              (default-challenger))
    (play-from-hand state :contestant "Ben Musashi" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)
    (core/gain state :challenger :credit 1)
    (let [bm (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (core/reveal state :contestant bm)
      (run-empty-server state "Server 1")
      ;; challenger now chooses which to access.
      (prompt-select :challenger bm)
      (prompt-choice :challenger "Yes") ; pay to trash
      (prompt-select :challenger hok)
      ;; should now have prompt to pay 2 net for HoK
      (prompt-choice :challenger "Yes")
      (is (= 2 (count (:discard (get-challenger)))) "Challenger took 2 net")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest ben-musashi-obokata
  ;; Check challenger chooses order of payment
  (do-game
    (new-game (default-contestant [(qty "Ben Musashi" 1) (qty "Obokata Protocol" 1)])
              (default-challenger [(qty "Sure Gamble" 6)]))
    (play-from-hand state :contestant "Ben Musashi" "New remote")
    (play-from-hand state :contestant "Obokata Protocol" "Server 1")
    (take-credits state :contestant)
    (let [bm (get-content state :remote1 0)
          op (get-content state :remote1 1)]
      (core/reveal state :contestant bm)
      (run-empty-server state "Server 1")
      ;; challenger now chooses which to access.
      (prompt-select :challenger op)
      ;; prompt should be asking for the net damage costs
      (is (= "Obokata Protocol" (:title (:card (first (:prompt (get-challenger))))))
          "Prompt to pay steal costs")
      (prompt-choice :challenger "2 net damage")
      (is (= 2 (count (:discard (get-challenger)))) "Challenger took 2 net damage")
      (is (= 0 (count (:scored (get-challenger)))) "No scored agendas")
      (prompt-choice :challenger "4 net damage")
      (is (= 5 (count (:discard (get-challenger)))) "Challenger took 4 net damage")
      (is (= 1 (count (:scored (get-challenger)))) "Scored agenda"))))

(deftest ben-musashi-fetal-ai
  ;; Check Fetal AI can be stolen #2586
  (do-game
    (new-game (default-contestant [(qty "Ben Musashi" 1) (qty "Fetal AI" 1)])
              (default-challenger [(qty "Sure Gamble" 5)]))
    (play-from-hand state :contestant "Ben Musashi" "New remote")
    (play-from-hand state :contestant "Fetal AI" "Server 1")
    (take-credits state :contestant)
    (let [bm (get-content state :remote1 0)
          fai (get-content state :remote1 1)]
      (core/reveal state :contestant bm)
      (run-empty-server state "Server 1")
      ;; challenger now chooses which to access.
      (prompt-select :challenger fai)
      (prompt-choice :challenger "Access")
      ;; prompt should be asking for the net damage costs
      (is (= "Fetal AI" (:title (:card (first (:prompt (get-challenger))))))
          "Prompt to pay steal costs")
      (prompt-choice :challenger "2 [Credits]")
      (is (= 3 (:credit (get-challenger))) "Challenger paid 2 credits")
      (is (= 0 (count (:scored (get-challenger)))) "No scored agendas")
      (prompt-choice :challenger "2 net damage")
      (is (= 4 (count (:discard (get-challenger)))) "Challenger took 4 net damage - 2 from Fetal, 2 from Ben")
      (is (= 1 (count (:scored (get-challenger)))) "Scored agenda"))))

(deftest berncharacter-mai
  ;; Berncharacter Mai - successful and unsuccessful
  (do-game
    (new-game (default-contestant [(qty "Berncharacter Mai" 3) (qty "Hedge Fund" 3) (qty "Wall of Static" 3)])
              (default-challenger))
    (starting-hand state :contestant ["Berncharacter Mai" "Berncharacter Mai" "Berncharacter Mai"])
    (play-from-hand state :contestant "Berncharacter Mai" "New remote")
    (play-from-hand state :contestant "Berncharacter Mai" "New remote")
    (play-from-hand state :contestant "Berncharacter Mai" "R&D")
    (core/reveal state :contestant (get-content state :remote1 0))
    (take-credits state :contestant)
    (run-empty-server state :remote1)
    (prompt-choice :contestant 0)
    (prompt-choice :challenger 0)
    (prompt-choice :challenger "Yes")
    (is (= 1 (:tag (get-challenger))))
    (is (= 2 (:credit (get-challenger))) "Challenger paid 3cr to trash Berncharacter")
    (core/reveal state :contestant (get-content state :remote2 0))
    (core/gain state :challenger :credit 20)
    (run-empty-server state :remote2)
    (prompt-choice :contestant 0)
    (prompt-choice :challenger 10)
    (is (not (get-content state :remote2 0)) "Berncharacter auto-trashed from unsuccessful trace")
    (is (not (:run @state)) "Run ended when Berncharacter was trashed from server")
    (core/reveal state :contestant (get-content state :rd 0))
    (run-empty-server state :rd)
    (prompt-choice :contestant 0)
    (prompt-choice :challenger 10)
    (is (:card (first (:prompt (get-challenger)))) "Accessing a card from R&D; not showing Berncharacter Mai as possible access")))

(deftest berncharacter-mai-drt
  ;; Berncharacter Mai - interaction with Dedicated Response Team
  (do-game
    (new-game (default-contestant [(qty "Berncharacter Mai" 3) (qty "Dedicated Response Team" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Berncharacter Mai" "New remote")
    (play-from-hand state :contestant "Dedicated Response Team" "New remote")
    (core/reveal state :contestant (get-content state :remote1 0))
    (core/reveal state :contestant (get-content state :remote2 0))
    (take-credits state :contestant)
    (run-empty-server state :remote1)
    (prompt-choice :contestant 0)
    (prompt-choice :challenger 0)
    (prompt-choice :challenger "Yes")
    (is (= 1 (:tag (get-challenger))))
    (is (= 2 (:credit (get-challenger))) "Challenger paid 3cr to trash Berncharacter")
    (is (= 2 (count (:discard (get-challenger)))) "Challenger took 1 meat damage")))

(deftest breaker-bay-grid
  ;; Breaker Bay Grid - Reduce reveal cost of other cards in this server by 5 credits
  (do-game
   (new-game (default-contestant [(qty "Breaker Bay Grid" 2) (qty "The Root" 1) (qty "Strongbox" 1)])
             (default-challenger))
   (core/gain state :contestant :click 1)
   (play-from-hand state :contestant "Breaker Bay Grid" "New remote")
   (play-from-hand state :contestant "The Root" "Server 1")
   (let [bbg1 (get-content state :remote1 0)
         root (get-content state :remote1 1)]
     (core/reveal state :contestant bbg1)
     (core/reveal state :contestant root)
     (is (= 4 (:credit (get-contestant))) "Paid only 1 to reveal The Root")
     (play-from-hand state :contestant "Breaker Bay Grid" "R&D")
     (play-from-hand state :contestant "Strongbox" "R&D")
     (let [bbg2 (get-content state :rd 0)
           sbox (get-content state :rd 1)]
       (core/reveal state :contestant bbg2)
       (core/reveal state :contestant sbox)
       (is (= 1 (:credit (get-contestant))) "Paid full 3 credits to reveal Strongbox")))))

(deftest calibration-testing
  ;; Calibration Testing - advanceable / non-advanceable
  (do-game
    (new-game (default-contestant [(qty "Calibration Testing" 2) (qty "Project Junebug" 1) (qty "PAD Campaign" 1)])
              (default-challenger))
    (core/gain state :contestant :credit 10)
    (core/gain state :contestant :click 1)
    (play-from-hand state :contestant "Calibration Testing" "New remote")
    (play-from-hand state :contestant "Project Junebug" "Server 1")
    (let [ct (get-content state :remote1 0)
          pj (get-content state :remote1 1)]
      (core/reveal state :contestant ct)
      (card-ability state :contestant ct 0)
      (prompt-select :contestant pj)
      (is (= 1 (:advance-counter (refresh pj))) "Project Junebug advanced")
      (is (= 1 (count (:discard (get-contestant)))) "Calibration Testing trashed"))
    (play-from-hand state :contestant "Calibration Testing" "New remote")
    (play-from-hand state :contestant "PAD Campaign" "Server 2")
    (let [ct (get-content state :remote2 0)
          pad (get-content state :remote2 1)]
      (core/reveal state :contestant ct)
      (card-ability state :contestant ct 0)
      (prompt-select :contestant pad)
      (is (= 1 (:advance-counter (refresh pad))) "PAD Campaign advanced")
      (is (= 2 (count (:discard (get-contestant)))) "Calibration Testing trashed"))))

(deftest caprcharacter-nisei
  ;; Caprcharacter Nisei - Psi game for ETR after challenger passes last character
  (do-game
   (new-game (default-contestant [(qty "Caprcharacter Nisei" 3) (qty "Quandary" 3)])
             (default-challenger))
   (play-from-hand state :contestant "Caprcharacter Nisei" "New remote")
   (take-credits state :contestant)
   (let [caprcharacter (get-content state :remote1 0)]
     ;; Check Caprcharacter triggers properly on no character (and revealed)
     (core/reveal state :contestant caprcharacter)
     (run-on state "Server 1")
     (is (prompt-is-card? :contestant caprcharacter)
         "Caprcharacter prompt even with no character, once challenger makes run")
     (is (prompt-is-card? :challenger caprcharacter) "Challenger has Caprcharacter prompt")
     (prompt-choice :contestant "0 [Credits]")
     (prompt-choice :challenger "1 [Credits]")
     (take-credits state :challenger)


     (play-from-hand state :contestant "Quandary" "Server 1")
     (play-from-hand state :contestant "Quandary" "Server 1")
     (take-credits state :contestant)

     ;; Check Caprcharacter triggers properly on multiple character
     (run-on state "Server 1")
     (run-continue state)
     (is (empty? (get-in @state [:contestant :prompt])) "Caprcharacter not trigger on first character")
     (run-continue state) ; Caprcharacter prompt after this
     (is (prompt-is-card? :contestant caprcharacter)
         "Contestant has Caprcharacter prompt (triggered automatically as challenger passed last character)")
     (is (prompt-is-card? :challenger caprcharacter) "Challenger has Caprcharacter prompt")
     (prompt-choice :contestant "0 [Credits]")
     (prompt-choice :challenger "1 [Credits]")
     (is (not (:run @state)) "Run ended by Caprcharacter")
     (is (empty? (get-in @state [:contestant :prompt])) "Caprcharacter prompted cleared")

     ;; Check Caprcharacter does not trigger on other servers
     (run-on state "HQ")
     (is (empty? (get-in @state [:contestant :prompt])) "Caprcharacter does not trigger on other servers"))))

(deftest chilo-city-grid
  ;; ChiLo City Grid - Give 1 tag for successful traces during runs on its server
  (do-game
    (new-game (default-contestant [(qty "Caduceus" 2) (qty "ChiLo City Grid" 1)])
              (default-challenger))
    (play-from-hand state :contestant "ChiLo City Grid" "New remote")
    (play-from-hand state :contestant "Caduceus" "Server 1")
    (take-credits state :contestant)
    (let [chilo (get-content state :remote1 0)
          cad (get-character state :remote1 0)]
      (run-on state "R&D")
      (core/reveal state :contestant cad)
      (core/reveal state :contestant chilo)
      (card-subroutine state :contestant cad 0)
      (prompt-choice :contestant 0)
      (prompt-choice :challenger 0)
      (is (= 3 (:credit (get-contestant))) "Trace was successful")
      (is (= 0 (:tag (get-challenger))) "No tags given for run on different server")
      (run-successful state)
      (run-on state "Server 1")
      (card-subroutine state :contestant cad 0)
      (prompt-choice :contestant 0)
      (prompt-choice :challenger 0)
      (is (= 6 (:credit (get-contestant))) "Trace was successful")
      (is (= 1 (:tag (get-challenger)))
          "Challenger took 1 tag given from successful trace during run on ChiLo server"))))

(deftest contestantorate-troubleshooter
  ;; Contestantorate Troubleshooter - Pay X credits and trash to add X strength to a piece of revealed Character
  (do-game
    (new-game (default-contestant [(qty "Quandary" 2) (qty "Contestantorate Troubleshooter" 1)])
              (default-challenger))
    (core/gain state :contestant :credit 5)
    (play-from-hand state :contestant "Contestantorate Troubleshooter" "HQ")
    (play-from-hand state :contestant "Quandary" "HQ")
    (play-from-hand state :contestant "Quandary" "HQ")
    (let [ct (get-content state :hq 0)
          q1 (get-character state :hq 0)
          q2 (get-character state :hq 1)]
      (core/reveal state :contestant q1)
      (is (= 8 (:credit (get-contestant))))
      (core/reveal state :contestant ct)
      (card-ability state :contestant ct 0)
      (prompt-choice :contestant 5)
      (prompt-select :contestant q2)
      (is (nil? (:current-strength (refresh q2))) "Outer Quandary unrevealed; can't be targeted")
      (prompt-select :contestant q1)
      (is (= 5 (:current-strength (refresh q1))) "Inner Quandary boosted to 5 strength")
      (is (empty? (get-content state :hq))
          "Contestantorate Troubleshooter trashed from root of HQ")
      (take-credits state :contestant)
      (is (= 0 (:current-strength (refresh q1)))
          "Inner Quandary back to default 0 strength after turn ends"))))

(deftest crisium-grid
  ;; Crisium Grid - various interactions
  (do-game
    (new-game (default-contestant [(qty "Crisium Grid" 2)])
              (default-challenger [(qty "Desperado" 1) (qty "Temüjin Contract" 1)]))
    (play-from-hand state :contestant "Crisium Grid" "HQ")
    (core/reveal state :contestant (get-content state :hq 0))
    (take-credits state :contestant)
    (is (= 4 (:credit (get-contestant))) "Contestant has 4 credits")
    (core/gain state :challenger :credit 4)
    (play-from-hand state :challenger "Desperado")
    (play-from-hand state :challenger "Temüjin Contract")
    (prompt-choice :challenger "HQ")
    (run-empty-server state "HQ")
    (is (= 2 (:credit (get-challenger))) "No Desperado or Temujin credits")
    (is (not (:successful-run (:register (get-challenger)))) "No successful run in register")))

(deftest cyberdex-virus-suite-purge
  ;; Cyberdex Virus Suite - Purge ability
  (do-game
    (new-game (default-contestant [(qty "Cyberdex Virus Suite" 3)])
              (default-challenger [(qty "Cache" 1) (qty "Medium" 1)]))
    (play-from-hand state :contestant "Cyberdex Virus Suite" "HQ")
    (take-credits state :contestant 2)
    ;; challenger's turn
    ;; install cache and medium
    (play-from-hand state :challenger "Cache")
    (let [virus-counters (fn [card] (core/get-virus-counters state :challenger (refresh card)))
          cache (find-card "Cache" (get-in @state [:challenger :rig :resource]))
          cvs (get-content state :hq 0)]
      (is (= 3 (virus-counters cache)))
      (play-from-hand state :challenger "Medium")
      (take-credits state :challenger 2)
      (core/reveal state :contestant cvs)
      (card-ability state :contestant cvs 0)
      ;; nothing in hq content
      (is (empty? (get-content state :hq)) "CVS was trashed")
      ;; purged counters
      (is (zero? (virus-counters cache))
          "Cache has no counters")
      (is (zero? (virus-counters (find-card "Medium" (get-in @state [:challenger :rig :resource]))))
          "Medium has no counters"))))

(deftest cyberdex-virus-suite-access
  ;; Cyberdex Virus Suite - Purge on access
  (do-game
    (new-game (default-contestant [(qty "Cyberdex Virus Suite" 3)])
              (default-challenger [(qty "Cache" 1) (qty "Medium" 1)]))
    (play-from-hand state :contestant "Cyberdex Virus Suite" "New remote")
    (take-credits state :contestant 2)
    ;; challenger's turn
    ;; install cache and medium
    (play-from-hand state :challenger "Cache")
    (let [virus-counters (fn [card] (core/get-virus-counters state :challenger (refresh card)))
          cache (find-card "Cache" (get-in @state [:challenger :rig :resource]))
          cvs (get-content state :remote1 0)]
      (is (= 3 (virus-counters cache)))
      (play-from-hand state :challenger "Medium")
      (run-empty-server state "Server 1")
      ;; contestant now has optional prompt to trigger virus purge
      (prompt-choice :contestant "Yes")
      ;; challenger has prompt to trash CVS
      (prompt-choice :challenger "Yes")
      ;; purged counters
      (is (zero? (virus-counters cache))
          "Cache has no counters")
      (is (zero? (virus-counters (find-card "Medium" (get-in @state [:challenger :rig :resource]))))
          "Medium has no counters"))))

(deftest cyberdex-virus-suite-archives-access
  ;; Cyberdex Virus Suite - Don't interrupt archives access. Issue #1647.
  (do-game
    (new-game (default-contestant [(qty "Cyberdex Virus Suite" 1) (qty "Braintrust" 1)])
              (default-challenger [(qty "Cache" 1)]))
    (trash-from-hand state :contestant "Cyberdex Virus Suite")
    (trash-from-hand state :contestant "Braintrust")
    (take-credits state :contestant)
    ;; challenger's turn
    ;; install cache
    (play-from-hand state :challenger "Cache")
    (let [cache (get-resource state 0)]
      (is (= 3 (get-counters (refresh cache) :virus)))
      (run-empty-server state "Archives")
      (prompt-choice :challenger "Cyberdex Virus Suite")
      (prompt-choice :contestant "Yes")
      (is (pos? (count (:prompt (get-challenger)))) "CVS purge did not interrupt archives access")
      ;; purged counters
      (is (zero? (get-counters (refresh cache) :virus))
          "Cache has no counters"))))

(deftest forced-connection
  ;; Forced Connection - ambush, trace(3) give the challenger 2 tags
  (do-game
    (new-game (default-contestant [(qty "Forced Connection" 3)])
              (default-challenger))
    (starting-hand state :contestant ["Forced Connection" "Forced Connection"])
    (play-from-hand state :contestant "Forced Connection" "New remote")
    (take-credits state :contestant)
    (is (= 0 (:tag (get-challenger))) "Challenger starts with 0 tags")
    (run-empty-server state :remote1)
    (prompt-choice :contestant 0)
    (prompt-choice :challenger 0)
    (prompt-choice :challenger "Yes") ; trash
    (is (= 2 (:tag (get-challenger))) "Challenger took two tags")
    (run-empty-server state "Archives")
    (is (= 2 (:tag (get-challenger))) "Challenger doesn't take tags when accessed from Archives")
    (run-empty-server state "HQ")
    (prompt-choice :contestant 0)
    (prompt-choice :challenger 3)
    (prompt-choice :challenger "Yes") ; trash
    (is (= 2 (:tag (get-challenger))) "Challenger doesn't take tags when trace won")))

(deftest ghost-branch-dedicated-response-team
  ;; Ghost Branch - with Dedicated Response Team
  (do-game
    (new-game (default-contestant [(qty "Ghost Branch" 1) (qty "Dedicated Response Team" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Ghost Branch" "New remote")
    (play-from-hand state :contestant "Dedicated Response Team" "New remote")
    (core/gain state :contestant :click 1)
    (let [gb (get-content state :remote1 0)
          drt (get-content state :remote2 0)]
      (core/advance state :contestant {:card gb})
      (core/advance state :contestant {:card (refresh gb)})
      (is (= 2 (:advance-counter (refresh gb))) "Ghost Branch advanced twice")
      (take-credits state :contestant)
      (run-on state "Server 1")
      (core/reveal state :contestant drt)
      (run-successful state)
      (is (prompt-is-type? :challenger :waiting) "Challenger has prompt to wait for Ghost Branch")
      (prompt-choice :contestant "Yes")
      (is (= 2 (:tag (get-challenger))) "Challenger has 2 tags")
      (prompt-choice :challenger "Yes")
      (is (= 2 (count (:discard (get-challenger)))) "Challenger took 2 meat damage"))))

(deftest georgia-emelyov
  ;; Georgia Emelyov
  (do-game
    (new-game (default-contestant [(qty "Georgia Emelyov" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Georgia Emelyov" "New remote")
    (let [geo (get-content state :remote1 0)]
      (core/reveal state :contestant geo)
      (take-credits state :contestant)
      (run-on state "Server 1")
      (run-jack-out state)
      (is (= 1 (count (:discard (get-challenger)))) "Challenger took 1 net damage")
      (card-ability state :contestant (refresh geo) 0)
      (prompt-choice :contestant "Archives")
      (let [geo (get-content state :archives 0)]
        (is geo "Georgia moved to Archives")
        (run-on state "Archives")
        (run-jack-out state)
        (is (= 2 (count (:discard (get-challenger)))) "Challenger took 1 net damage")
        (run-on state "HQ")
        (run-jack-out state)
        (is (= 2 (count (:discard (get-challenger)))) "Challenger did not take damage")))))

(deftest helheim-servers
  ;; Helheim Servers - Full test
  (do-game
    (new-game (default-contestant [(qty "Helheim Servers" 1) (qty "Gutenberg" 1) (qty "Vanilla" 1)
                             (qty "Jackson Howard" 1) (qty "Hedge Fund" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Helheim Servers" "R&D")
    (play-from-hand state :contestant "Gutenberg" "R&D")
    (play-from-hand state :contestant "Vanilla" "R&D")
    (take-credits state :contestant)
    (run-on state "R&D")
    (is (:run @state))
    (let [helheim (get-content state :rd 0)
          gutenberg (get-character state :rd 0)
          vanilla (get-character state :rd 1)]
      (core/reveal state :contestant helheim)
      (core/reveal state :contestant gutenberg)
      (core/reveal state :contestant vanilla)
      (is (= 6 (:current-strength (refresh gutenberg))))
      (is (= 0 (:current-strength (refresh vanilla))))
      (card-ability state :contestant helheim 0)
      (prompt-select :contestant (find-card "Jackson Howard" (:hand (get-contestant))))
      (is (= 1 (count (:discard (get-contestant)))))
      (is (= 8 (:current-strength (refresh gutenberg))))
      (is (= 2 (:current-strength (refresh vanilla))))
      (card-ability state :contestant helheim 0)
      (prompt-select :contestant (find-card "Hedge Fund" (:hand (get-contestant))))
      (is (= 2 (count (:discard (get-contestant)))))
      (is (= 10 (:current-strength (refresh gutenberg))))
      (is (= 4 (:current-strength (refresh vanilla))))
      (run-jack-out state)
      (is (not (:run @state)))
      (is (= 6 (:current-strength (refresh gutenberg))))
      (is (= 0 (:current-strength (refresh vanilla)))))))

(deftest hokusai-grid
  ;; Hokusai Grid - Do 1 net damage when run successful on its server
  (do-game
    (new-game (default-contestant [(qty "Hokusai Grid" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Hokusai Grid" "HQ")
    (take-credits state :contestant)
    (core/reveal state :contestant (get-content state :hq 0))
    (run-empty-server state :rd)
    (is (empty? (:discard (get-challenger))) "No net damage done for successful run on R&D")
    (run-empty-server state :hq)
    (is (= 1 (count (:discard (get-challenger)))) "1 net damage done for successful run on HQ")))

(deftest jinja-city-grid
  ;; Jinja City Grid - install drawn character, lowering install cost by 4
  (do-game
    (new-game (default-contestant [(qty "Jinja City Grid" 1) (qty "Vanilla" 3) (qty "Ice Wall" 3)])
              (default-challenger))
    (starting-hand state :contestant ["Jinja City Grid"])
    (core/gain state :contestant :click 6)
    (play-from-hand state :contestant "Jinja City Grid" "New remote")
    (core/reveal state :contestant (get-content state :remote1 0))
    (dotimes [n 5]
      (core/click-draw state :contestant 1)
      (prompt-choice :contestant "Yes")
      (is (= 4 (:credit (get-contestant))) "Not charged to install character")
      (is (= (inc n) (count (get-in @state [:contestant :servers :remote1 :characters]))) (str n " Character protecting Remote1")))
    (core/click-draw state :contestant 1)
    (prompt-choice :contestant "Yes")
    (is (= 3 (:credit (get-contestant))) "Charged to install character")
    (is (= 6 (count (get-in @state [:contestant :servers :remote1 :characters]))) "6 Character protecting Remote1")))

(deftest keegan-lane
  ;; Keegan Lane - Trash self and remove 1 Challenger tag to trash a resource
  (do-game
    (new-game (default-contestant [(qty "Keegan Lane" 1)])
              (default-challenger [(qty "Corroder" 1)]))
    (play-from-hand state :contestant "Keegan Lane" "HQ")
    (take-credits state :contestant)
    (play-from-hand state :challenger "Corroder")
    (run-on state :hq)
    (let [keeg (get-content state :hq 0)]
      (core/reveal state :contestant keeg)
      (card-ability state :contestant keeg 0)
      (is (= 1 (count (get-content state :hq))) "Keegan didn't fire, Challenger has no tags")
      (core/gain state :challenger :tag 2)
      (card-ability state :contestant keeg 0)
      (prompt-select :contestant (get-resource state 0))
      (is (= 1 (:tag (get-challenger))) "1 tag removed")
      (is (= 1 (count (:discard (get-contestant)))) "Keegan trashed")
      (is (= 1 (count (:discard (get-challenger)))) "Corroder trashed"))))

(deftest manta-grid
  ;; If the Challenger has fewer than 6 or no unspent clicks on successful run, contestant gains a click next turn.
  (do-game
    (new-game (default-contestant [(qty "Manta Grid" 1)])
              (default-challenger))
    (starting-hand state :challenger [])
    (is (= 3 (:click (get-contestant))) "Contestant has 3 clicks")
    (play-from-hand state :contestant "Manta Grid" "HQ")
    (core/reveal state :contestant (get-content state :hq 0))
    (take-credits state :contestant)
    (core/click-draw state :challenger nil)
    (core/click-draw state :challenger nil)
    (run-empty-server state "HQ")
    (prompt-choice :challenger "No") ; don't trash Manta Grid
    (is (= 1 (:click (get-challenger))) "Running last click")
    (run-empty-server state "HQ")
    (prompt-choice :challenger "No") ; don't trash Manta Grid
    (take-credits state :challenger)
    (is (= 5 (:click (get-contestant))) "Contestant gained 2 clicks due to 2 runs with < 6 Challenger credits")
    (take-credits state :contestant)
    (take-credits state :challenger)
    (is (= 3 (:click (get-contestant))) "Contestant back to 3 clicks")
    (take-credits state :contestant)
    (take-credits state :challenger 3)
    (run-empty-server state "HQ")
    (prompt-choice :challenger "No") ; don't trash Manta Grid
    (take-credits state :challenger)
    (is (= 4 (:click (get-contestant))) "Contestant gained a click due to running last click")))

(deftest marcus-batty-security-nexus
  ;; Marcus Batty - Simultaneous Interaction with Security Nexus
  (do-game
    (new-game (default-contestant [(qty "Marcus Batty" 1) (qty "Enigma" 1)])
              (default-challenger [(qty "Security Nexus" 1)]))
    (play-from-hand state :contestant "Marcus Batty" "HQ")
    (play-from-hand state :contestant "Enigma" "HQ")
    (take-credits state :contestant)
    (core/gain state :challenger :credit 8)
    (play-from-hand state :challenger "Security Nexus")
    (let [mb (get-content state :hq 0)
          en (get-character state :hq 0)
          sn (-> @state :challenger :rig :hazard first)]
      (run-on state "HQ")
      (core/reveal state :contestant mb)
      (core/reveal state :contestant en)
      (card-ability state :contestant mb 0)
      (card-ability state :challenger sn 0)
      ;; both prompts should be on Batty
      (is (prompt-is-card? :contestant mb) "Contestant prompt is on Marcus Batty")
      (is (prompt-is-card? :challenger mb) "Challenger prompt is on Marcus Batty")
      (prompt-choice :contestant "0")
      (prompt-choice :challenger "0")
      (is (prompt-is-card? :contestant sn) "Contestant prompt is on Security Nexus")
      (is (prompt-is-type? :challenger :waiting) "Challenger prompt is waiting for Contestant"))))

(deftest mumbad-virtual-tour-force-trash
  ;; Tests that Mumbad Virtual Tour forces trash when no :slow-trash
  (do-game
    (new-game (default-contestant [(qty "Mumbad Virtual Tour" 2)])
              (default-challenger))
    (play-from-hand state :contestant "Mumbad Virtual Tour" "New remote")
    (take-credits state :contestant)
    (run-empty-server state "HQ")
    ;; MVT does not force trash when not installed
    (prompt-choice :challenger "No")
    (is (= 5 (:credit (get-challenger))) "Challenger not forced to trash MVT in HQ")
    (is (empty? (:discard (get-contestant))) "MVT in HQ is not trashed")
    (run-empty-server state "Server 1")
    ;; Toast should show at this point to notify challenger they were forced to trash MVT
    (is (= 0 (:credit (get-challenger))) "Challenger forced to trash MVT")
    (is (= "Mumbad Virtual Tour" (:title (first (:discard (get-contestant))))) "MVT trashed")))

(deftest mumbad-virtual-tour-slow-trash
  ;; Tests that Mumbad Virtual Tour does not force trash with :slow-trash
  (do-game
    (new-game (default-contestant [(qty "Mumbad Virtual Tour" 2)])
              (default-challenger [(qty "Imp" 1)]))
    (play-from-hand state :contestant "Mumbad Virtual Tour" "New remote")
    (play-from-hand state :contestant "Mumbad Virtual Tour" "New remote")
    (take-credits state :contestant)
    (play-from-hand state :challenger "Imp")
    ;; Reset credits to 5
    (core/gain state :challenger :credit 2)
    (run-empty-server state "Server 1")
    ;; Challenger not force to trash since Imp is installed
    (is (= 5 (:credit (get-challenger))) "Challenger not forced to trash MVT when Imp installed")
    (is (empty? (:discard (get-contestant))) "MVT is not force-trashed when Imp installed")
    (let [imp (get-resource state 0)]
      (card-ability state :challenger imp 0)
      (is (= "Mumbad Virtual Tour" (:title (first (:discard (get-contestant)))))
          "MVT trashed with Imp")
      ;; Trash Imp to reset :slow-trash flag
      (core/move state :challenger (refresh imp) :discard)
      (is (not (core/any-flag-fn? state :challenger :slow-trash true))))))

(deftest neotokyo-grid
  ;; NeoTokyo Grid - Gain 1c the first time per turn a card in this server gets an advancement
  (do-game
    (new-game (default-contestant [(qty "NeoTokyo Grid" 1) (qty "Nisei MK II" 1)
                             (qty "Shipment from SanSan" 1) (qty "Ice Wall" 1)])
              (default-challenger))
    (core/gain state :contestant :click 2)
    (play-from-hand state :contestant "NeoTokyo Grid" "New remote")
    (play-from-hand state :contestant "Nisei MK II" "Server 1")
    (core/reveal state :contestant (get-content state :remote1 0))
    (let [nis (get-content state :remote1 1)]
      (play-from-hand state :contestant "Shipment from SanSan")
      (prompt-choice :contestant "2")
      (prompt-select :contestant nis)
      (is (= 2 (:advance-counter (refresh nis))) "2 advancements on agenda")
      (is (= 4 (:credit (get-contestant))) "Gained 1 credit")
      (core/advance state :contestant {:card (refresh nis)})
      (is (= 3 (:advance-counter (refresh nis))) "3 advancements on agenda")
      (is (= 3 (:credit (get-contestant))) "No credit gained")
      (take-credits state :contestant)
      (take-credits state :challenger)
      (play-from-hand state :contestant "Ice Wall" "Server 1")
      (core/advance state :contestant {:card (refresh (get-character state :remote1 0))})
      (is (= 2 (:credit (get-contestant))) "No credit gained from advancing Character"))))

(deftest off-the-grid
  ;; Off the Grid run ability - and interaction with RP
  (do-game
   (new-game
    (make-deck "Jinteki: Replicating Perfection" [(qty "Off the Grid" 3)
                                                  (qty "Mental Health Clinic" 3)])
    (default-challenger))
   (play-from-hand state :contestant "Off the Grid" "New remote")
   (play-from-hand state :contestant "Mental Health Clinic" "Server 1")
   (let [otg (get-content state :remote1 0)]
     (take-credits state :contestant)
     (core/reveal state :contestant (refresh otg))
     (is (not (core/can-run-server? state "Server 1")) "Challenger can only run on centrals")
     (run-empty-server state "R&D")
     (is (not (core/can-run-server? state "Server 1")) "Challenger cannot run on Off the Grid")
     (take-credits state :challenger)
     (take-credits state :contestant)
     (is (not (core/can-run-server? state "Server 1")) "Off the Grid prevention persisted")
     (run-empty-server state "HQ")
     (is (boolean (core/can-run-server? state "Server 1")) "Challenger can run on Server 1")
     (is (= nil (refresh otg)) "Off the Grid trashed"))))

(deftest old-hollywood-grid
  ;; Old Hollywood Grid - Ability
  (do-game
    (new-game (default-contestant [(qty "Old Hollywood Grid" 1) (qty "House of Knives" 3)])
              (default-challenger))
    (play-from-hand state :contestant "Old Hollywood Grid" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)
    (let [ohg (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (run-on state "Server 1")
      (core/reveal state :contestant ohg)
      (run-successful state)
      ;; challenger now chooses which to access.
      (prompt-select :challenger hok)
      ;; prompt shows "You cannot steal"
      (prompt-choice :challenger "OK")
      (is (= 0 (count (:scored (get-challenger)))) "No stolen agendas")
      (prompt-select :challenger ohg)
      (prompt-choice :challenger "No")
      (core/steal state :challenger (find-card "House of Knives" (:hand (get-contestant))))
      (run-empty-server state "Server 1")
      (prompt-select :challenger hok)
      (prompt-choice :challenger "Yes")
      (is (= 2 (count (:scored (get-challenger)))) "2 stolen agendas"))))

(deftest old-hollywood-grid-central
  ;; Old Hollywood Grid - Central server
  (do-game
    (new-game (default-contestant [(qty "Old Hollywood Grid" 1) (qty "House of Knives" 3)])
              (default-challenger))
    (play-from-hand state :contestant "Old Hollywood Grid" "HQ")
    (take-credits state :contestant 2)
    (let [ohg (get-content state :hq 0)]
      (run-on state "HQ")
      (core/reveal state :contestant ohg)
      (run-successful state)
      ;; challenger now chooses which to access.
      (prompt-choice :challenger "Card from hand")
      ;; prompt shows "You cannot steal"
      (prompt-choice :challenger "OK")
      (is (= 0 (count (:scored (get-challenger)))) "No stolen agendas")
      (prompt-choice :challenger "Old Hollywood Grid")
      ;; trash OHG
      (prompt-choice :challenger "Yes")
      (run-empty-server state "HQ")
      (prompt-choice :challenger "Steal")
      (is (= 1 (count (:scored (get-challenger)))) "1 stolen agenda"))))

(deftest old-hollywood-grid-gang-sign
  ;; Old Hollywood Grid - Gang Sign interaction. Prevent the steal outside of a run. #2169
  (do-game
    (new-game (default-contestant [(qty "Old Hollywood Grid" 1) (qty "Project Beale" 2)])
              (default-challenger [(qty "Gang Sign" 1)]))
    (play-from-hand state :contestant "Old Hollywood Grid" "HQ")
    (play-from-hand state :contestant "Project Beale" "New remote")
    (take-credits state :contestant)
    (play-from-hand state :challenger "Gang Sign")
    (take-credits state :challenger)
    (core/reveal state :contestant (get-content state :hq 0))
    (score-agenda state :contestant (get-content state :remote1 0))
    ;; Gang sign fires
    (prompt-choice :challenger "Card from hand")
    ;; prompt shows "You cannot steal"
    (prompt-choice :challenger "OK")
    (is (= 0 (count (:scored (get-challenger)))) "No stolen agendas")))

(deftest port-anson-grid
  ;; Port Anson Grid - Prevent the Challenger from jacking out until they trash a resource
  (do-game
    (new-game (default-contestant [(qty "Port Anson Grid" 1) (qty "Data Raven" 1)])
              (default-challenger [(qty "Faerie" 1) (qty "Technical Writer" 1)]))
    (play-from-hand state :contestant "Port Anson Grid" "New remote")
    (play-from-hand state :contestant "Data Raven" "Server 1")
    (take-credits state :contestant)
    (play-from-hand state :challenger "Technical Writer")
    (play-from-hand state :challenger "Faerie")
    (let [pag (get-content state :remote1 0)
          fae (get-in @state [:challenger :rig :resource 0])
          tw (get-in @state [:challenger :rig :muthereff 0])]
      (run-on state "Server 1")
      (core/reveal state :contestant pag)
      (is (:cannot-jack-out (get-in @state [:run])) "Jack out disabled for Challenger") ; UI button greyed out
      (core/trash state :challenger tw)
      (is (:cannot-jack-out (get-in @state [:run])) "Muthereff trash didn't disable jack out prevention")
      (core/trash state :challenger fae)
      (is (nil? (:cannot-jack-out (get-in @state [:run]))) "Jack out enabled by resource trash")
      (run-on state "Server 1")
      (is (:cannot-jack-out (get-in @state [:run])) "Prevents jack out when region is revealed prior to run"))))

(deftest prisec
  ;; Prisec - Pay 2 credits to give challenger 1 tag and do 1 meat damage, only when installed
  (do-game
    (new-game (default-contestant [(qty "Prisec" 2)])
              (default-challenger))
    (play-from-hand state :contestant "Prisec" "New remote")
    (take-credits state :contestant)
    (run-empty-server state "Server 1")
    (let [pre-creds (:credit (get-contestant))]
      (prompt-choice :contestant "Yes")
      (is (= (- pre-creds 2) (:credit (get-contestant))) "Pay 2 [Credits] to pay for Prisec"))
    (is (= 1 (:tag (get-challenger))) "Give challenger 1 tag")
    (is (= 1 (count (:discard (get-challenger)))) "Prisec does 1 damage")
    ;; Challenger trashes Prisec
    (prompt-choice :challenger "Yes")
    (run-empty-server state "HQ")
    (is (not (:prompt @state)) "Prisec does not trigger from HQ")))

(deftest prisec-dedicated-response-team
  ;; Multiple unrevealed regions in Archives interaction with DRT.
  (do-game
    (new-game (default-contestant [(qty "Prisec" 2) (qty "Dedicated Response Team" 1)])
              (default-challenger [(qty "Sure Gamble" 3) (qty "Diesel" 3)]))
    (play-from-hand state :contestant "Dedicated Response Team" "New remote")
    (play-from-hand state :contestant "Prisec" "Archives")
    (play-from-hand state :contestant "Prisec" "Archives")
    (core/gain state :contestant :click 1 :credit 14)
    (core/reveal state :contestant (get-content state :remote1 0))
    (take-credits state :contestant)

    (run-empty-server state :archives)
    (is (:run @state) "Run still active")
    (prompt-choice :challenger "Unrevealed region in Archives")
    (prompt-select :challenger (get-content state :archives 0))
    (prompt-choice :contestant "Yes") ; contestant pay for PriSec
    (prompt-choice :challenger "No") ; challenger don't pay to trash
    (is (:run @state) "Run still active")
    (prompt-choice :challenger "Unrevealed region in Archives")
    (prompt-choice :contestant "Yes") ; contestant pay for PriSec
    (prompt-choice :challenger "No") ; challenger don't pay to trash
    (is (not (:run @state)) "Run ended")
    (is (= 4 (count (:discard (get-challenger)))) "Challenger took 4 meat damage")))

(deftest product-placement
  ;; Product Placement - Gain 2 credits when Challenger accesses it
  (do-game
    (new-game (default-contestant [(qty "Product Placement" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Product Placement" "New remote")
    (take-credits state :contestant)
    (is (= 7 (:credit (get-contestant))))
    (let [pp (get-content state :remote1 0)]
      (run-empty-server state "Server 1")
      (is (= 9 (:credit (get-contestant))) "Gained 2 credits from Challenger accessing Product Placement")
      (prompt-choice :challenger "Yes") ; Challenger trashes PP
      (run-empty-server state "Archives")
      (is (= 9 (:credit (get-contestant)))
          "No credits gained when Product Placement accessed in Archives"))))

(deftest red-herrings
  ;; Red Herrings - Ability
  (do-game
    (new-game (default-contestant [(qty "Red Herrings" 1) (qty "House of Knives" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Red Herrings" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)

    (let [rh (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (core/reveal state :contestant rh)
      (run-empty-server state "Server 1")
      ;; challenger now chooses which to access.
      (prompt-select :challenger hok)
      ;; prompt should be asking for the 5cr cost
      (is (= "House of Knives" (:title (:card (first (:prompt (get-challenger))))))
          "Prompt to pay 5cr")
      (prompt-choice :challenger "No")
      (is (= 5 (:credit (get-challenger))) "Challenger was not charged 5cr")
      (is (= 0 (count (:scored (get-challenger)))) "No scored agendas")
      (prompt-select :challenger rh)
      (prompt-choice :challenger "No")
      (run-empty-server state "Server 1")
      (prompt-select :challenger hok)
      (prompt-choice :challenger "Yes")
      (is (= 0 (:credit (get-challenger))) "Challenger was charged 5cr")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest red-herrings-trash
  ;; Red Herrings - Cost increase even when trashed
  (do-game
    (new-game (default-contestant [(qty "Red Herrings" 3) (qty "House of Knives" 3)])
              (default-challenger))
    (play-from-hand state :contestant "Red Herrings" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)
    (core/gain state :challenger :credit 1)
    (let [rh (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (core/reveal state :contestant rh)
      (run-empty-server state "Server 1")
      ;; challenger now chooses which to access.
      (prompt-select :challenger rh)
      (prompt-choice :challenger "Yes") ; pay to trash
      (prompt-select :challenger hok)
      ;; should now have prompt to pay 5cr for HoK
      (prompt-choice :challenger "Yes")
      (is (= 0 (:credit (get-challenger))) "Challenger was charged 5cr")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest red-herrings-trash-from-hand
  ;; Red Herrings - Trashed from Hand
  (do-game
    (new-game (default-contestant [(qty "Red Herrings" 1) (qty "House of Knives" 1)])
              (default-challenger))
    (trash-from-hand state :contestant "Red Herrings")
    (is (= 1 (count (:discard (get-contestant)))) "1 card in Archives")
    (take-credits state :contestant)

    (run-empty-server state "HQ")
    ;; prompt should be asking to steal HoK
    (is (= "Steal" (first (:choices (first (:prompt (get-challenger))))))
        "Challenger being asked to Steal")))

(deftest red-herrings-other-server
  ;; Red Herrings - Don't affect runs on other servers
  (do-game
    (new-game (default-contestant [(qty "Red Herrings" 1) (qty "House of Knives" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Red Herrings" "New remote")
    (play-from-hand state :contestant "House of Knives" "New remote")
    (take-credits state :contestant 1)

    (let [rh (get-content state :remote1 0)]
      (core/reveal state :contestant rh)
      (run-empty-server state "Server 2")
      ;; access is automatic
      (prompt-choice :challenger "Steal")
      (is (= 5 (:credit (get-challenger))) "Challenger was not charged 5cr")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest ruhr-valley
  ;; Ruhr Valley - As an additional cost to make a run on this server, the Challenger must spend a click.
  (do-game
    (new-game (default-contestant [(qty "Ruhr Valley" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Ruhr Valley" "HQ")
    (take-credits state :contestant)
    (let [ruhr (get-content state :hq 0)]
      (core/reveal state :contestant ruhr)
      (is (= 4 (:click (get-challenger))))
      (run-on state :hq)
      (run-jack-out state)
      (is (= 2 (:click (get-challenger))))
      (take-credits state :challenger 1)
      (is (= 1 (:click (get-challenger))))
      (is (not (core/can-run-server? state "HQ")) "Challenger can't run - no additional clicks")
      (take-credits state :challenger)
      (take-credits state :contestant)
      (is (= 4 (:click (get-challenger))))
      (is (= 7 (:credit (get-challenger))))
      (run-on state :hq)
      (run-successful state)
      (prompt-choice :challenger "Yes") ; pay to trash / 7 cr - 4 cr
      (is (= 2 (:click (get-challenger))))
      (is (= 3 (:credit (get-challenger))))
      (run-on state :hq)
      (run-jack-out state)
      (is (= 1 (:click (get-challenger)))))))

(deftest ruhr-valley-enable-state
  ;; Ruhr Valley - If the challenger trashes with one click left, the ability to run is enabled
  (do-game
    (new-game (default-contestant [(qty "Ruhr Valley" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Ruhr Valley" "HQ")
    (take-credits state :contestant)
    (let [ruhr (get-content state :hq 0)]
      (core/reveal state :contestant ruhr)
      (is (= 4 (:click (get-challenger))))
      (run-on state :rd)
      (run-jack-out state)
      (is (= 3 (:click (get-challenger))))
      (run-on state :hq)
      (run-successful state)
      (prompt-choice :challenger "Yes") ; pay to trash / 6 cr - 4 cr
      (is (= 1 (:click (get-challenger))))
      (run-on state :hq))))

(deftest ryon-knight
  ;; Ryon Knight - Trash during run to do 1 brain damage if Challenger has no clicks remaining
  (do-game
    (new-game (default-contestant [(qty "Ryon Knight" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Ryon Knight" "HQ")
    (take-credits state :contestant)
    (let [ryon (get-content state :hq 0)]
      (run-on state :hq)
      (core/reveal state :contestant ryon)
      (card-ability state :contestant ryon 0)
      (is (= 3 (:click (get-challenger))))
      (is (= 0 (:brain-damage (get-challenger))))
      (is (= 1 (count (get-content state :hq))) "Ryon ability didn't fire with 3 Challenger clicks left")
      (run-jack-out state)
      (take-credits state :challenger 2)
      (run-on state :hq)
      (card-ability state :contestant ryon 0)
      (is (= 0 (:click (get-challenger))))
      (is (= 1 (:brain-damage (get-challenger))) "Did 1 brain damage")
      (is (= 1 (count (:discard (get-contestant)))) "Ryon trashed"))))

(deftest satellite-grid
  ;; Satellite Grid - Add 1 fake advancement on all Character protecting server
  (do-game
    (new-game (default-contestant [(qty "Satellite Grid" 1) (qty "Ice Wall" 2)])
              (default-challenger))
    (play-from-hand state :contestant "Satellite Grid" "HQ")
    (play-from-hand state :contestant "Ice Wall" "HQ")
    (play-from-hand state :contestant "Ice Wall" "R&D")
    (let [iw1 (get-character state :hq 0)
          iw2 (get-character state :rd 0)
          sg (get-content state :hq 0)]
      (core/gain state :contestant :click 1)
      (advance state iw1)
      (core/reveal state :contestant sg)
      (core/reveal state :contestant (refresh iw1))
      (is (= 1 (:extra-advance-counter (refresh iw1))) "1 fake advancement token")
      (is (= 1 (:advance-counter (refresh iw1))) "Only 1 real advancement token")
      (is (= 3 (:current-strength (refresh iw1))) "Satellite Grid counter boosting strength by 1")
      (core/reveal state :contestant (refresh iw2))
      (is (= 1 (:current-strength (refresh iw2))) "Satellite Grid not impacting Character elsewhere")
      (core/hide state :contestant sg)
      (is (= 2 (:current-strength (refresh iw1))) "Ice Wall strength boost only from real advancement"))))

(deftest signal-jamming
  ;; Trash to stop installs for the rest of the run
  (do-game
    (new-game (default-contestant [(qty "Signal Jamming" 3)])
              (default-challenger [(qty "Self-modifying Code" 3) (qty "Reaver" 1)]))
    (starting-hand state :challenger ["Self-modifying Code" "Self-modifying Code"])
    (play-from-hand state :contestant "Signal Jamming" "HQ")
    (take-credits state :contestant)
    (play-from-hand state :challenger "Self-modifying Code")
    (play-from-hand state :challenger "Self-modifying Code")
    (let [smc1 (get-in @state [:challenger :rig :resource 0])
          smc2 (get-in @state [:challenger :rig :resource 1])
          sj (get-content state :hq 0)]
      (core/reveal state :contestant sj)
      (run-on state "HQ")
      (run-continue state)
      (card-ability state :contestant sj 0)
      (card-ability state :challenger smc1 0)
      (is (empty? (:prompt (get-challenger))) "SJ blocking SMC")
      (run-jack-out state)
      (card-ability state :challenger smc2 0)
      (prompt-card :challenger (find-card "Reaver" (:deck (get-challenger)))))))

(deftest strongbox
  ;; Strongbox - Ability
  (do-game
    (new-game (default-contestant [(qty "Strongbox" 1) (qty "House of Knives" 1)])
              (default-challenger))
    (play-from-hand state :contestant "Strongbox" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)

    (let [sb (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (core/reveal state :contestant sb)
      (run-empty-server state "Server 1")
      (prompt-select :challenger hok)
      (is (= "House of Knives" (:title (:card (first (:prompt (get-challenger))))))
          "Prompt to pay 5cr")
      (prompt-choice :challenger "No")
      (is (= 3 (:click (get-challenger))) "Challenger was not charged 1click")
      (is (= 0 (count (:scored (get-challenger)))) "No scored agendas")
      (prompt-select :challenger sb)
      (prompt-choice :challenger "No")
      (run-empty-server state "Server 1")
      (prompt-select :challenger hok)
      (prompt-choice :challenger "Yes")
      (is (= 1 (:click (get-challenger))) "Challenger was charged 1click")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest strongbox-trash
  ;; Strongbox - Click cost even when trashed
  (do-game
    (new-game (default-contestant [(qty "Strongbox" 3) (qty "House of Knives" 3)])
              (default-challenger))
    (play-from-hand state :contestant "Strongbox" "New remote")
    (play-from-hand state :contestant "House of Knives" "Server 1")
    (take-credits state :contestant 1)

    (core/gain state :challenger :credit 1)
    (let [sb (get-content state :remote1 0)
          hok (get-content state :remote1 1)]
      (core/reveal state :contestant sb)
      (run-empty-server state "Server 1")
      (prompt-select :challenger sb)
      (prompt-choice :challenger "Yes") ; pay to trash
      (prompt-select :challenger hok)
      (prompt-choice :challenger "Yes")
      (is (= 2 (:click (get-challenger))) "Challenger was charged 1click")
      (is (= 1 (count (:scored (get-challenger)))) "1 scored agenda"))))

(deftest surat-city-grid
  ;; Surat City Grid - Trigger on reveal of a card in/protecting same server to reveal another card at 2c discount
  (do-game
    (new-game (default-contestant [(qty "Surat City Grid" 2) (qty "Cyberdex Virus Suite" 2)
                             (qty "Enigma" 1) (qty "Wraparound" 1)])
              (default-challenger))
    (core/gain state :contestant :credit 15 :click 8)
    (play-from-hand state :contestant "Surat City Grid" "New remote")
    (play-from-hand state :contestant "Wraparound" "Server 1")
    (play-from-hand state :contestant "Cyberdex Virus Suite" "Server 1")
    (let [scg1 (get-content state :remote1 0)
          cvs1 (get-content state :remote1 1)
          wrap (get-character state :remote1 0)]
      (core/reveal state :contestant scg1)
      (core/reveal state :contestant cvs1)
      (is (= 15 (:credit (get-contestant))))
      (is (= (:cid scg1) (-> (get-contestant) :prompt first :card :cid)) "Surat City Grid triggered from region in same remote")
      (prompt-choice :contestant "Yes")
      (prompt-select :contestant wrap)
      (is (get-in (refresh wrap) [:revealed]) "Wraparound is revealed")
      (is (= 15 (:credit (get-contestant))) "Wraparound revealed for free with 2c discount from SCG")
      (play-from-hand state :contestant "Surat City Grid" "HQ")
      (play-from-hand state :contestant "Enigma" "HQ")
      (play-from-hand state :contestant "Cyberdex Virus Suite" "HQ")
      (let [scg2 (get-content state :hq 0)
            cvs2 (get-content state :hq 1)
            enig (get-character state :hq 0)]
        (core/reveal state :contestant scg2)
        (core/reveal state :contestant cvs2)
        (is (empty? (:prompt (get-contestant))) "SCG didn't trigger, regions in root of same central aren't considered in server")
        (core/hide state :contestant (refresh wrap))
        (core/reveal state :contestant enig)
        (is (= (:cid scg2) (-> (get-contestant) :prompt first :card :cid)) "SCG did trigger for Character protecting HQ")))))

(deftest tori-hanzo
  ;; Tori Hanzō - Pay to do 1 brain damage instead of net damage
  (do-game
    (new-game (default-contestant [(qty "Pup" 1) (qty "Tori Hanzō" 1)])
              (default-challenger [(qty "Sure Gamble" 3) (qty "Net Shield" 1)]))
    (core/gain state :contestant :credit 10)
    (play-from-hand state :contestant "Pup" "HQ")
    (play-from-hand state :contestant "Tori Hanzō" "HQ")
    (take-credits state :contestant)
    (play-from-hand state :challenger "Net Shield")
    (run-on state "HQ")
    (let [pup (get-character state :hq 0)
          tori (get-content state :hq 0)
          nshld (get-in @state [:challenger :rig :resource 0])]
      (core/reveal state :contestant pup)
      (core/reveal state :contestant tori)
      (card-subroutine state :contestant pup 0)
      (card-ability state :challenger nshld 0)
      (prompt-choice :challenger "Done")
      (is (empty? (:discard (get-challenger))) "1 net damage prevented")
      (card-subroutine state :contestant pup 0)
      (prompt-choice :challenger "Done") ; decline to prevent
      (is (= 1 (count (:discard (get-challenger)))) "1 net damage; previous prevention stopped Tori ability")
      (run-jack-out state)
      (run-on state "HQ")
      (card-subroutine state :contestant pup 0)
      (prompt-choice :challenger "Done")
      (prompt-choice :contestant "Yes")
      (is (= 2 (count (:discard (get-challenger)))) "1 brain damage suffered")
      (is (= 1 (:brain-damage (get-challenger)))))))

(deftest tori-hanzo-hokusai
  ;; Tori Hanzō + Hokusai Grid: Issue #2702
  (do-game
    (new-game (default-contestant [(qty "Tori Hanzō" 1) (qty "Hokusai Grid" 1)])
              (default-challenger))
    (core/gain state :contestant :credit 5)
    (play-from-hand state :contestant "Hokusai Grid" "Archives")
    (play-from-hand state :contestant "Tori Hanzō" "Archives")
    (take-credits state :contestant)
    (run-on state "Archives")
    (let [hg (get-content state :archives 0)
          tori (get-content state :archives 1)]
      (core/reveal state :contestant hg)
      (core/reveal state :contestant tori)
      (run-successful state)
      (prompt-choice :contestant "No") ; Tori prompt to pay 2c to replace 1 net with 1 brain
      (is (= 1 (count (:discard (get-challenger)))) "1 net damage suffered")
      (prompt-choice :challenger "Hokusai Grid")
      (prompt-choice :challenger "No")
      (prompt-choice :challenger "Tori Hanzō")
      (prompt-choice :challenger "No")
      (is (and (empty (:prompt (get-challenger))) (not (:run @state))) "No prompts, run ended")
      (run-empty-server state "Archives")
      (prompt-choice :contestant "Yes") ; Tori prompt to pay 2c to replace 1 net with 1 brain
      (is (= 2 (count (:discard (get-challenger)))))
      (is (= 1 (:brain-damage (get-challenger))) "1 brain damage suffered")
      (prompt-choice :challenger "Hokusai Grid")
      (prompt-choice :challenger "No")
      (prompt-choice :challenger "Tori Hanzō")
      (prompt-choice :challenger "No")
      (is (and (empty (:prompt (get-challenger))) (not (:run @state))) "No prompts, run ended"))))

(deftest underway-grid
  ;; Underway Grid - prevent expose of cards in server
  (do-game
    (new-game (default-contestant [(qty "Eve Campaign" 1)
                             (qty "Underway Grid" 1)])
              (default-challenger [(qty "Drive By" 1)]))
    (play-from-hand state :contestant "Underway Grid" "New remote")
    (play-from-hand state :contestant "Eve Campaign" "Server 1")
    (take-credits state :contestant)
    (core/reveal state :contestant (get-content state :remote1 0))
    (let [eve1 (get-content state :remote1 1)]
      (play-from-hand state :challenger "Drive By")
      (prompt-select :challenger eve1)
      (is (empty? (:discard (get-contestant))) "Expose and trash prevented"))))

(deftest valley-grid-trash
  ;; Valley Grid - Reduce Challenger max hand size and restore it even if trashed
  (do-game
    (new-game (default-contestant [(qty "Valley Grid" 3) (qty "Ice Wall" 3)])
              (default-challenger))
    (play-from-hand state :contestant "Valley Grid" "New remote")
    (take-credits state :contestant 2)
    (run-on state "Server 1")
    (let [vg (get-content state :remote1 0)]
      (core/reveal state :contestant vg)
      (card-ability state :contestant vg 0)
      (card-ability state :contestant vg 0) ; only need the run to exist for test, just pretending the Challenger has broken all subs on 2 character
      (is (= 3 (core/hand-size state :challenger)) "Challenger max hand size reduced by 2")
      (is (= 2 (get-in (refresh vg) [:times-used])) "Saved number of times Valley Grid used")
      (run-successful state)
      (prompt-choice :challenger "Yes") ; pay to trash
      (take-credits state :challenger 3)
      (is (= 5 (core/hand-size state :challenger)) "Challenger max hand size increased by 2 at start of Contestant turn"))))
