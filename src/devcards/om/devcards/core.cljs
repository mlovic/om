(ns om.devcards.core
  (:require-macros [devcards.core :refer [defcard deftest]])
  (:require [cljs.test :refer-macros [is async]]
            [om.devcards.tutorials]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]))

(enable-console-print!)

(defui Hello
  Object
  (render [this]
    (dom/p nil (-> this om/props :text))))

(def hello (om/factory Hello))

(defcard simple-component
  "Test that Om Next component work as regular React components."
  (hello {:text "Hello, world!"}))

(def p
  (om/parser
    {:read   (fn [_ _ _] {:remote true})
     :mutate (fn [_ _ _] {:remote true})}))

(def r
  (om/reconciler
    {:parser p
     :ui->ref (fn [c] (-> c om/props :id))}))

(defui Binder
  Object
  (componentDidMount [this]
    (let [indexes @(get-in (-> this om/props :reconciler) [:config :indexer])]
      (om/update-state! this assoc :indexes indexes)))
  (render [this]
    (binding [om/*reconciler* (-> this om/props :reconciler)]
      (apply dom/div nil
        (hello {:id 0 :text "Goodbye, world!"})
        (when-let [indexes (get-in (om/get-state this)
                             [:indexes :ref->components])]
          [(dom/p nil (pr-str indexes))])))))

(def binder (om/factory Binder))

(defcard basic-nested-component
  "Test that component nesting works"
  (binder {:reconciler r}))

(deftest test-indexer
  "Test indexer"
  (let [idxr (get-in r [:config :indexer])]
    (is (not (nil? idxr)) "Indexer is not nil in the reconciler")
    (is (not (nil? @idxr)) "Indexer is IDeref")))

;; -----------------------------------------------------------------------------
;; Counteres

(defmulti counters-read (fn [_ k] k))

(defmulti counters-mutate (fn [_ k _] k))

(defmethod counters-read :default
  [{:keys [state]} k params]
  (let [st @state]
    (if (contains? st k)
      {:value (get st k)}
      {:remote true})))

(defmethod counters-read :counters/list
  [{:keys [state selector]} _]
  (let [st @state
        xf (map #(select-keys (get-in st %) selector))]
    {:value (into [] xf (:counters/list st))}))

(defmethod counters-mutate 'counter/increment
  [{:keys [state ref]} _ _]
  {:value []
   :action
   (fn []
     (swap! state update-in (conj ref :counter/count) inc))})

(defmethod counters-mutate 'counters/delete
  [{:keys [state ref]} _ _]
  {:value [:counters/list]
   :action
   (fn []
     (swap! state
       (fn [state]
         (-> state
           (update-in (pop ref) dissoc (peek ref))
           (update-in [:counters/list] #(vec (remove #{ref} %)))))))})

(defmethod counters-mutate 'counters/create
  [{:keys [state]} _ _]
  {:value [:counters/list]
   :action
   (fn []
     (swap! state
       (fn [state]
         (let [id (:app/current-id state)
               counter {:id id :counter/count 0}]
           (-> state
             (assoc-in [:app/counters id] counter)
             (update-in [:counters/list] conj [:app/counters id])
             (update-in [:app/current-id] inc))))))})

;; -----------------------------------------------------------------------------
;; Counter

(defui Counter
  static om/IQuery
  (query [this]
    '[:id :counter/count])
  om/Ident
  (ident [this {:keys [id]}]
    [:app/counters id])
  Object
  (initLocalState [this]
    {:state-count 0})
  (componentWillUnmount [this]
    (println "Buh-bye!"))
  (componentWillUpdate [this next-props next-state]
    (println "component will update" (om/props this) next-props))
  (componentDidUpdate [this prev-props prev-state]
    #_(println "component did update" (om/props this) prev-props))
  (render [this]
    (println "Shared Counter" (om/shared this))
    (let [{:keys [:counter/count] :as props} (om/props this)]
      (dom/div nil
        (dom/p nil
          (str "Count: " count
            " State Count: " (om/get-state this :state-count)))
        (dom/button
          #js {:onClick
               (fn [_] (om/transact! this '[(counter/increment)]))}
          "Update Props, Click Me!")
        (dom/button
          #js {:style #js {:marginLeft "10px"}
               :onClick
               (fn [_] (om/update-state! this update-in [:state-count] inc))}
          "Update State, Click Me!")
        (dom/button
          #js {:style #js {:marginLeft "10px"}
               :onClick
               (fn [_] (om/transact! this '[(counters/delete) :counters/list]))}
          "Delete")))))

(def counter (om/factory Counter {:keyfn :id}))

;; -----------------------------------------------------------------------------
;; CountersAppTitle

(defui CountersAppTitle
  Object
  (render [this]
    (apply dom/div nil
      (om/children this))))

(def counters-app-title (om/factory CountersAppTitle))

;; -----------------------------------------------------------------------------
;; CountersApp

(defui CountersApp
  static om/IQueryParams
  (params [this]
    {:counter (om/get-query Counter)})
  static om/IQuery
  (query [this]
    '[:app/title {:counters/list ?counter}])
  Object
  (render [this]
    (println "Shared CountersApp" (om/shared this))
    (let [{:keys [:app/title :counters/list] :as props}
          (om/props this)]
      (apply dom/div nil
        (counters-app-title nil
          (dom/h2 #js {:key "a"} "Hello World!")
          (dom/h3 #js {:key "b"} "cool stuff"))
        (dom/div nil
          (dom/button
            #js {:onClick (fn [e] (om/transact! this '[(counters/create)]))}
            "Add Counter!"))
        (map counter list)))))

;; -----------------------------------------------------------------------------
;; Reconciler setup

(def counters-app-state
  (atom {:app/title "Hello World!"
         :app/current-id 3
         :app/counters
         {0 {:id 0 :counter/count 0}
          1 {:id 1 :counter/count 0}
          2 {:id 2 :counter/count 0}}
         :counters/list [[:app/counters 0]
                         [:app/counters 1]
                         [:app/counters 2]]}))

(def counters-reconciler
  (om/reconciler
    {:state     counters-app-state
     :parser    (om/parser {:read counters-read :mutate counters-mutate})
     :shared-fn (fn [_] {:foo :bar})}))

(defcard test-counters
  "Test that we can mock a reconciler backed Om Next component into devcards"
  (om/mock-root counters-reconciler CountersApp))

(defcard test-counters-atom
  (om/app-state counters-reconciler))

;; -----------------------------------------------------------------------------
;; Children

(defui Children
  Object
  (render [this]
    (dom/div nil
      (map identity
        #js [(dom/div nil "Foo")
             (dom/div nil "Bar")
             (map identity
               #js [(dom/div nil "Bar")
                    (dom/div nil "Woz")])]))))

(def children (om/factory Children))

(defcard test-lazy-children
  "Test that lazy sequences as elements works. This permits React.js style
   splicing."
  (children))

;; -----------------------------------------------------------------------------
;; Simple Recursive Query Syntax

(def simple-tree-data
  {:tree {:node-value 1
          :children [{:node-value 2
                      :children [{:node-value 3
                                  :children []}]}
                     {:node-value 4
                      :children []}]}})

(declare simple-node)

(defui SimpleNode
  static om/IQuery
  (query [this]
    '[:node-value {:children ...}])
  Object
  (render [this]
    (let [{:keys [node-value children]} (om/props this)]
      (dom/li nil
        (dom/div nil (str "Node value:" node-value))
        (dom/ul nil
          (map simple-node children))))))

(def simple-node (om/factory SimpleNode))

(defui SimpleTree
  static om/IQuery
  (query [this]
    [{:tree (om/get-query SimpleNode)}])
  Object
  (render [this]
    (let [{:keys [tree]} (om/props this)]
      (dom/ul nil
        (simple-node tree)))))

(defmulti simple-tree-read om/dispatch)

(defmethod simple-tree-read :node-value
  [{:keys [data] :as env} _ _]
  {:value (:node-value data)})

(defmethod simple-tree-read :children
  [{:keys [data parser selector] :as env} _ _]
  {:value (let [f #(parser (assoc env :data %) selector)]
            (into [] (map f (:children data))))})

(defmethod simple-tree-read :tree
  [{:keys [state parser selector] :as env} k _]
  (let [st @state]
    {:value (parser (assoc env :data (:tree st)) selector)}))

(def simple-tree-reconciler
  (om/reconciler
    {:state     (atom simple-tree-data)
     :normalize false
     :parser    (om/parser {:read simple-tree-read})}))

(defcard test-simple-recursive-syntax
  "Test that `'[:node-value {:children ...}]` syntax works."
  (om/mock-root simple-tree-reconciler SimpleTree))

;; -----------------------------------------------------------------------------
;; Recursive Query Syntax with Mutations

(def norm-tree-data
  {:tree {:id 0
          :node-value 1
          :children [{:id 1
                      :node-value 2
                      :children [{:id 2
                                  :node-value 3
                                  :children []}]}
                     {:id 3
                      :node-value 4
                      :children []}]}})

(declare norm-node)

(defui NormNode
  static om/Ident
  (ident [this {:keys [id]}]
    [:node/by-id id])
  static om/IQuery
  (query [this]
    '[:id :node-value {:children ...}])
  Object
  (render [this]
    (let [{:keys [node-value children]} (om/props this)]
      (dom/li nil
        (dom/div nil (str "Node value:" node-value))
        (dom/ul nil
          (map norm-node children))))))

(def norm-node (om/factory NormNode))

(defui NormTree
  static om/IQuery
  (query [this]
    [{:tree (om/get-query NormNode)}])
  Object
  (render [this]
    (let [{:keys [tree]} (om/props this)]
      (dom/ul nil
        (norm-node tree)))))

(defmulti norm-tree-read om/dispatch)

(defmethod norm-tree-read :tree
  [{:keys [state selector] :as env} _ _]
  (let [st @state]
    {:value (om/db->tree selector (:tree st) st)}))

(def norm-tree-parser
  (om/parser {:read norm-tree-read}))

(def norm-tree-reconciler
  (om/reconciler
    {:state  norm-tree-data
     :parser norm-tree-parser}))

(defcard test-simple-recursive-syntax-with-mutation
  "Test that simple recursive syntax works with mutations and component
   local state."
  (om/mock-root norm-tree-reconciler NormTree))

;; -----------------------------------------------------------------------------
;; Layered Recursive Query Syntax