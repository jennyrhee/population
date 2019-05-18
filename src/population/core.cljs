(ns population.core
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            ["chart.js" :as Chart]
            [population.viruses :as virus]))

;; define your app data so that it doesn't get over-written on reload

(def default-db
  {:simulations {:simple {:parameters {:number-of-viruses 100
                                       :max-population 1000
                                       :max-birth-prob 0.1
                                       :clear-prob 0.05
                                       :number-of-trials 300}
                          :partial-results []
                          :results []}}})

(rf/reg-event-db
  :initialize-db
  (fn [_ _] default-db))

(rf/reg-sub
  :simulation-results
  (fn [db _]
    (get-in db [:simulations :simple])))

(rf/reg-event-db
  :add-results
  (fn [db [_ virus-type results]]
    (-> db
        (update-in [:simulations virus-type :partial-results]
                   into results))))

(rf/reg-sub
  :get-in
  (fn [db [_ path]]
    (get-in db path)))

(rf/reg-event-db
  :assoc-in
  (fn [db [_ path value]]
    (assoc-in db path value)))

(defn simulate-simple [{:keys [number-of-viruses max-population
                               max-birth-prob clear-prob number-of-trials]}]
  (let [step (fn step [viruses]
               (loop [alive []
                      q viruses]
                 (if-let [v (first q)]
                   (recur (cond-> alive
                            (not (virus/dies? v)) (conj v)

                            (virus/reproduces? v (/ (+ (count alive)
                                                       (count viruses))
                                                    max-population))
                            (conj (virus/reproduce v)))
                          (rest q))
                   alive)))
        initial-viruses (virus/simple-viruses number-of-viruses
                                              max-birth-prob
                                              clear-prob)
        perform-trial (fn [viruses]
                        (->> initial-viruses
                             (iterate step)
                             (take 300)
                             (mapv count)))]
    (into []
          (comp
            (take number-of-trials)
            (map perform-trial))
          (repeat initial-viruses))))

(comment
  (time (simulate-simple (-> default-db
                             :simulations
                             :simple
                             :parameters
                             (assoc :number-of-trials 15))))
  )

(defn partial-to-final
  [{:keys [partial-results parameters] :as simulation}]
  (let [finalized-results (->> partial-results
                               (apply map +)
                               (mapv #(/ % (:number-of-trials parameters))))]
    (prn (count finalized-results))
    (-> simulation
        (assoc :partial-results [])
        (assoc :results finalized-results))))

(rf/reg-event-db
  :finalize-simple
  (fn [db _]
    (let [number-of-trials (get-in db [:simulations
                                       :simple
                                       :parameters
                                       :number-of-trials])]
      (-> db
          (assoc :chart-spinning? false)
          (update-in [:simulations :simple] partial-to-final)))))

(rf/reg-event-fx
  :compute-simple
  (fn [_ [_ parameters]]
    (let [trials (:number-of-trials parameters)
          chunk-size (if (> trials 20)
                       20
                       trials)
          remaining (- trials chunk-size)
          followup (if (pos? remaining)
                     [:compute-simple (assoc parameters
                                             :number-of-trials
                                             remaining)]
                     [:finalize-simple])]
      {:dispatch-n [[:add-results :simple
                     (simulate-simple (assoc parameters :number-of-trials chunk-size))]
                    followup]})))

(rf/reg-event-fx
  :simulate-simple
  (fn [_ [_ parameters]]
    {:dispatch-n [[:assoc-in [:chart-spinning?] true]
                  [:assoc-in [:simulation :simple :parameters] parameters]
                  [:compute-simple parameters]]}))

(defn config [virus-type results]
  (clj->js
    {:type "line"
     :data {:labels (map #(str "Time " %) (range (count results)))
            :datasets
            [{:backgroundColor "red"
              :borderColor "red"
              :fill false
              :label (str "Virus count for " (name virus-type) " virus")
              :showLine true
              :data results}]}
     :options
     {:responsive true
      :scales {:xAxes [{:display true}]
               :yAxes [{:ticks {:beginAtZero true}}]}}}))

(defn chart-display [sim-type results]
  (let [!ref (atom nil)]
    (r/create-class
      {:display-name "results-chart"
       :component-did-mount (fn []
                              (when-let [com @!ref]
                                (Chart. (.getContext com "2d") (config sim-type results))))
       :reagent-render
       (fn [results]
         [:canvas {:ref (fn [com] (reset! !ref com))
                   :id (name sim-type)
                   :width "400px"
                   :height "300px"}])})))

(defn chart-container [sim-type results]
  [:div {:style {:display "flex"
                 :flex-direction "row"
                 :justify-content "center"
                 :align-items "center"
                 :margin "45px"}}
   [:div {:style {:width "800px"
                  :height "600px"}}
    [chart-display sim-type results]]])

(defn page []
  (let [parameters @(rf/subscribe [:get-in [:simulations :simple :parameters]])
        simple-results @(rf/subscribe [:get-in [:simulations :simple :results]])
        simple-partials @(rf/subscribe [:get-in [:simulations :simple :partial-results]])
        computing? @(rf/subscribe [:get-in [:chart-spinning?]])]
    [:div
     [:button {:on-click #(do
                            (rf/dispatch-sync [:assoc-in [:chart-spinning?] true])
                            (rf/dispatch [:simulate-simple parameters]))
               :disabled computing?} "Simulate Simple Virus"]
     (when computing?
       [:h1 "Performed "
        (-> simple-partials count)
        " trials of "
        (-> parameters
            :number-of-trials)])
     (when (seq simple-results)
       [chart-container :simple simple-results])]))

(defn start []
  (r/render [page]
            (. js/document (getElementById "app"))))

(defn ^:export init []
  ;; init is called ONCE when the page loads
  ;; this is called in the index.html and must be exported
  ;; so it is available even in :advanced release builds
  (rf/dispatch-sync [:initialize-db])
  (start))

(defn stop []
  ;; stop is called before any code is reloaded
  ;; this is controlled by :before-load in the config
  (js/console.log "stop"))
