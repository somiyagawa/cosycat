(ns cosycat.db.annotations
  (:require [monger.collection :as mc]
            [monger.query :as mq]
            [monger.operators :refer :all]
            [taoensso.timbre :as timbre]
            [cosycat.app-utils :refer [server-project-name]]
            [cosycat.utils :refer [assert-ex-info]]
            [cosycat.vcs :as vcs]))

;;; Exceptions
(defn span-or-token [{{B :B O :O :as scope} :scope}]
  (cond (and B O) "span"
        (integer? scope) "token"
        :else "undefined"))

(defn ex-overwrite-span [source-span span]
  (ex-info (str "Attempt to overwrite span annotation with "
                (span-or-token source-span) " annotation")
           {:source-span source-span :span span}))

(defn ex-overwrite-token [source-span span]
  (ex-info (str "Attempt to overwrite token annotation with "
                (span-or-token source-span) " annotation")
           {:source-span source-span :span span}))

(defn ex-overlapping-span [source-span span]
  (ex-info "Attempt to overwrite overlapping span annotation"
           {:source-span source-span :span span}))

;;; Checkers
(declare find-span-annotation-by-key find-token-annotation-by-key)

(defn check-span-overlap
  "checks if two span annotations overlap returning false if there is overlap"
  [{{{new-B :B new-O :O} :scope} :span :as new-ann}
   {{{old-B :B old-O :O} :scope} :span :as old-ann}]
  (cond
    (and (= new-B old-B) (= new-O old-O))   true
    (and (<= new-B old-O) (<= old-B new-O)) false
    :else true))

(defmulti check-insert (fn [db project {{scope-type :type} :span}] scope-type))

(defmethod check-insert "token"
  [{db-conn :db :as db} project {corpus :corpus {scope :scope :as span} :span {key :key} :ann}]
  (when-let [existing-token-annotation (find-token-annotation-by-key db project corpus key span)]
    (throw (ex-overwrite-token (:span existing-token-annotation) span)))
  (when-let [existing-span-annotation (find-span-annotation-by-key db project corpus key span)]
    (throw (ex-overwrite-span (:span existing-span-annotation) span))))

(defmethod check-insert "IOB"
  [{db-conn :db :as db} project {corpus :corpus {{B :B O :O} :scope :as span} :span {key :key} :ann}]
  (when-let [existing-token-annotation (find-token-annotation-by-key db project corpus key span)]
    (throw (ex-overwrite-token (:span existing-token-annotation) span)))
  (when-let [existing-span-annotation (find-span-annotation-by-key db project corpus key span)]
    (throw (ex-overlapping-span (:span existing-span-annotation) span))))

(defn check-sync-by-id
  "check if a document in a given collection is in sync with vcs database"
  [{db-conn :db :as db} project-name id claimed-version]
  (assert-ex-info
   (integer? claimed-version) "Version has to be integer" {:type (type claimed-version)})
  (let [coll (server-project-name project-name)]
    (if-let [{db-version :_version} (mc/find-one-as-map db-conn coll {:_id id})]
      (when-not (= db-version claimed-version)
        (let [message "Version mismatch"]
          (assert-ex-info
           message
           {:message message :code :version-mismatch
            :data {:db db-version :user claimed-version}}))))))

;;; Finders
(defn find-annotation-by-id [{db-conn :db} project-name id]
  (mc/find-one-as-map db-conn (server-project-name project-name) {:_id id}))

(defmulti find-span-annotation-by-key (fn [db project-name corpus ann-key {type :type}] type))

(defmethod find-span-annotation-by-key "token"
  [{db-conn :db} project-name corpus ann-key {scope :scope doc :doc}]
  (mc/find-one-as-map
   db-conn (server-project-name project-name)
   {"ann.key" ann-key
    "corpus" corpus
    "span.doc" doc
    $and [{"span.scope.B" {$lte scope}} {"span.scope.O" {$gte scope}}]}))

(defmethod find-span-annotation-by-key "IOB"
  [{db-conn :db} project-name corpus ann-key {{B :B O :O} :scope doc :doc}]
  (mc/find-one-as-map
   db-conn (server-project-name project-name)
   {"ann.key" ann-key
    "corpus" corpus
    "span.doc" doc
    $and [{"span.scope.B" {$lte O}} {"span.scope.O" {$gte B}}]}))

(defmulti find-token-annotation-by-key (fn [db project-name corpus ann-key {type :type}] type))

(defmethod find-token-annotation-by-key "token"
  [{db-conn :db} project-name corpus ann-key {scope :scope doc :doc}]
  (mc/find-one-as-map
   db-conn (server-project-name project-name)
   {"ann.key" ann-key
    "corpus" corpus
    "span.doc" doc
    "span.scope" scope}))

(defmethod find-token-annotation-by-key "IOB"
  [{db-conn :db} project-name corpus key {{B :B O :O} :scope doc :doc}]
  (mc/find-one-as-map
   db-conn (server-project-name project-name)
   {"ann.key" key
    "corpus" corpus
    "span.doc" doc
    "span.scope" {$in (range B (inc O))}}))

(defn with-history
  "wrapper function over vcs/with-history to avoid sending bson-objectids to the client"
  [db doc]
  (vcs/with-history db doc :on-history-doc #(dissoc % :_id :docId)))

(defn find-annotations
  [{db-conn :db :as db} project-name corpus from size
   & {:keys [history doc] :or {history true} :as opts}]
  (cond->> (mc/find-maps
            db-conn (server-project-name project-name)
            {"corpus" corpus
             "span.doc" doc ;if doc is null, get docs without span.doc and docs with span.doc == null
             $or [{$and [{"span.scope" {$gte from}} {"span.scope" {$lt (+ from size)}}]}
                  {$and [{"span.scope.B" {$lte (+ from size)}} {"span.scope.O" {$gt from}}]}]})
    history (mapv (partial with-history db-conn))))

(defn find-annotation-owner [db project-name ann-id]
  (-> (find-annotation-by-id db project-name ann-id) :username))

;;; Query annotations
(defn count-annotation-query
  "return a count of documents matching a given query-map"
  [{db-conn :db :as db} project-name query-map]
  (mc/count db-conn (server-project-name project-name) query-map))

(defn query-annotations
  "paginate over a query of annotations"
  [{db-conn :db :as db} project-name query-map page-num page-size
   & {:keys [with-history sort-fields] :or {with-history true sort-fields []}}]
  ;; (mc/find-maps db-conn (server-project-name project) query-map)
  (cond->> (mq/with-collection db-conn (server-project-name project-name)
             (mq/find query-map)
             (mq/sort (apply array-map (into ["span.scope" 1 "span.scope.B" 1] sort-fields)))
             (mq/paginate :page page-num :per-page page-size))
    with-history (map (partial vcs/with-history db-conn))))

;;; Setters
(defn insert-annotation
  [{db-conn :db :as db} project-name {username :username :as ann-map}]
  (check-insert db project-name ann-map)
  (timbre/info "Inserting annotation" (str ann-map))
  (let [ann-map (assoc ann-map :_id (vcs/new-uuid))]
    (vcs/insert-and-return db-conn (server-project-name project-name) ann-map)))

(defn update-annotation
  [{db-conn :db :as db} project-name
   {username :username timestamp :timestamp query :query hit-id :hit-id corpus :corpus
    value :value version :_version id :_id :as update-map}
   & {:keys [history] :or {history true}}]
  (assert-ex-info (and version id) "annotation update requires annotation id/version" update-map)
  (cond->> (vcs/find-and-modify
            db-conn (server-project-name project-name)
            version  
            {:_id id}
            {$set {"ann.value" value "timestamp" timestamp "username" username
                   "query" query "corpus" corpus "hit-id" hit-id}}
            {:return-new true})
    history (with-history db-conn)))

(defn remove-annotation
  [{db-conn :db :as db} project-name {version :_version id :_id :as ann-map}]
  (assert-ex-info (and version id) "annotation update requires annotation id/version" ann-map)
  (timbre/info "Removing annotation" (str ann-map))
  (vcs/remove-by-id db-conn (server-project-name project-name) version id))

(defn revert-annotation
  "updates annotation to lastest previous state"
  [{db-conn :db :as db} project-name {version :_version id :_id :as ann}]
  (if-let [history (vcs/find-history db-conn id)]
    (let [previous (first history)]
      (update-annotation db project-name (assoc previous :_version version)))))
