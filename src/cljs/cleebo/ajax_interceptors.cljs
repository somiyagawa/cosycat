(ns cleebo.ajax-interceptors
  (:require [ajax.core :refer [default-interceptors to-interceptor]]))

(defn csrf-interceptor [{:keys [csrf-token]}]
  (to-interceptor {:name "CSRF-Token interceptor"
                   :request #(assoc-in % [:params :csrf] csrf-token)}))

(defn ajax-header-interceptor []
  (to-interceptor {:name "AJAX-Header interceptor"
                   :request #(assoc-in % [:header "X-Requested-With"] "XMLHttpRequest")}))

(defn add-interceptor [interceptor & args]
  (swap! default-interceptors (partial cons (apply interceptor args))))