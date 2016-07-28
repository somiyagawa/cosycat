(ns cleebo.debug.page
  (:require [reagent.core :as reagent]
            [re-frame.core :as re-frame]
            [react-bootstrap.components :as bs]
            [cleebo.utils :refer [nbsp]]
            [cleebo.tree :refer [data-tree]]
            [cleebo.schemas.app-state-schemas :refer [db-schema]]
            [cleebo.localstorage :refer [dump-db fetch-last-dump get-backups]]
            [taoensso.timbre :as timbre]))

(defn ls-dump []
  [bs/button {:on-click dump-db} "Dump to LocalStorage"])

(defn ls-print []
  [bs/button {:on-click #(.log js/console (get-backups))} "Print LocalStorages to console"])

(defn ls-reload []
  [bs/button
   {:on-click #(if-let [dump (fetch-last-dump)]
                 (re-frame/dispatch [:load-db dump])
                 (timbre/info "No DBs in LocalStorage"))}
   "Reload last db from LocalStorage"])

(defn notification-button []
  [bs/button
   {:on-click
    #(re-frame/dispatch
      [:notify {:message "Hello World! How are you doing?"}])}
   "Notify"])

(defn debug-panel []
  (let [db (re-frame/subscribe [:db])]
    (fn []
      [:div.container-fluid
       [:div.row
        [:h3 [:span.text-muted "Debug Panel"]]]
       [:div.row [:hr]]
       [:div.row
        [bs/button-toolbar
         [notification-button]
         [ls-dump]
         [ls-print]
         [ls-reload]]]
       [:div.row [:hr]]
       [:div.row [data-tree @db]]])))
