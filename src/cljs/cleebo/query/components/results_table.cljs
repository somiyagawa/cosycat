(ns cleebo.query.components.results-table
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as timbre]
            [goog.dom :as gdom]
            [goog.dom.classes :as gclass]
            [goog.dom.dataset :as gdataset]
            [react-bootstrap.components :as bs]))

(defn on-mouse-down [mouse-down? highlighted?]
  (fn [event]
    (let [e      (aget event "target")
          button (aget event "button")]
      (.preventDefault event)                                 ;avoid text selection
      (when (and (zero? button) (not (gclass/has e "check"))) ;check button type
        (gclass/toggle e "highlighted")
        (swap! mouse-down? not)
        (reset! highlighted? (gclass/has e "highlighted"))
        (re-frame/dispatch
         [:mark-token
          {:hit-id (js/parseInt (gdataset/get (gdom/getParentElement e) "hit"))
           :token-id (gdataset/get e "id")
           :flag @highlighted?}])))))

(defn on-mouse-over [mouse-down? highlighted?]
  (fn [event]
    (let [e (aget event "target")
          button (aget event "button")]
      (when (and (zero? button) @mouse-down? (not (gclass/has e "check")))
        (gclass/enable e "highlighted" @highlighted?)
        (re-frame/dispatch
         [:mark-token
          {:hit-id (js/parseInt (gdataset/get (gdom/getParentElement e) "hit"))
           :token-id (gdataset/get e "id")
           :flag @highlighted?}])))))

(defn on-mouse-up [mouse-down? highlighted?]
  (fn [event]
    (let [button (aget event "button")
          e      (aget event "target")]
      (when (and (zero? button) (not (gclass/has e "check")))
        (swap! mouse-down? not)))))

(defn hit-token [{:keys [id word match marked anns]}]
  (fn [{:keys [id word match marked anns]}]
    (let [highlighted (if marked "highlighted " "")
          has-annotation (if anns "has-annotation " "")
          info (if match "info" "")]
      [:td
       {:class (str highlighted has-annotation info)
        :data-id id}
       word])))

(defn results-row [hit-num tabindex {:keys [hit id meta]}]
  (fn [hit-num tabindex {:keys [hit id meta]}]
    [:tr {:data-hit id}
     (concat
      ;; checkbox
      [^{:key (str hit-num "-check")}
       [:td.check {:style {:width "20px" :background-color "#F9F9F9"}}
        [:input.check {:type "checkbox"
                       :tab-index (inc tabindex)
                       :checked (:marked meta)
                       :on-change #(let [flag (.-checked (.-target %))]
                                     (re-frame/dispatch
                                      [:mark-hit
                                       {:hit-id id
                                        :flag flag}]))}]]
       ;; hit number
       ^{:key (str hit-num "-num")}
       [:td.check  {:style {:width "20px" :background-color "#F9F9F9"}}
        [:label.check {:style {:font-weight "bold"}} (inc hit-num)]]]
      ;; hit
      (for [token hit]
        ^{:key (str hit-num "-" (:id token))} [hit-token token]))]))

(defn results-table []
  (let [results (re-frame/subscribe [:results])
        from (re-frame/subscribe [:session :query-results :from])
        mouse-down? (reagent/atom false)
        highlighted? (reagent/atom false)]
    (fn []
      [bs/table
       {:responsive true
        :striped true
        :className "table-results"
        :id "table"
        :on-mouse-down (on-mouse-down mouse-down? highlighted?)        
        :on-mouse-over (on-mouse-over mouse-down? highlighted?)
        :on-mouse-up (on-mouse-up mouse-down? highlighted?)
        :tab-index 0
        :style {:border-collapse "collapse"}}
       [:thead]
       [:tbody {:style {:font-size "11px"}}
        (doall
         (for [[idx {:keys [hit meta id] :as hit-map}] (map-indexed vector @results)
               :let [hit-num (+ idx @from)]]
           ^{:key hit-num} [results-row hit-num idx hit-map]))]])))

