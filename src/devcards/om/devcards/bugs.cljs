(ns om.devcards.bugs
  (:require-macros [devcards.core :refer [defcard deftest dom-node]])
  (:require [cljs.test :refer-macros [is async]]
            [om.next :as om :refer-macros [defui]]
            [om.dom :as dom]))

(def init-data
  {:dashboard/posts
   [{:id 0 :favorites 0}]})

(defui Post
  static om/Ident
  (ident [this {:keys [id]}]
    [:post/by-id id])

  static om/IQuery
  (query [this]
    [:id :favorites])

  Object
  (render [this]
    (let [{:keys [id favorites] :as props} (om/props this)]
      (dom/div nil
        (dom/p nil "Favorites: " favorites)
        (dom/button
          #js {:onClick
               (fn [e]
                 (om/transact! this
                   `[(post/favorite {:id ~id})]))}
          "Favorite!")))))

(def post (om/factory Post))

(defui Dashboard
  static om/IQuery
  (query [this]
    `[({:dashboard/posts ~(om/get-query Post)} {:foo "bar"})])

  Object
  (render [this]
    (let [{:keys [dashboard/posts]} (om/props this)]
      (apply dom/ul nil
        (map post posts)))))

(defmulti read om/dispatch)

(defmethod read :dashboard/posts
  [{:keys [state query]} k _]
  (let [st @state]
    {:value (om/db->tree query (get st k) st)}))

(defmulti mutate om/dispatch)

(defmethod mutate 'post/favorite
  [{:keys [state]} k {:keys [id]}]
  {:action
   (fn []
     (swap! state update-in [:post/by-id id :favorites] inc))})

(def reconciler
  (om/reconciler
    {:state  init-data
     :parser (om/parser {:read read :mutate mutate})}))

(defcard test-om-466
  "Test that Parameterized joins work"
  (dom-node
    (fn [_ node]
      (om/add-root! reconciler Dashboard node))))

;; ==================
;; OM-552

(defui Child
  Object
  (componentWillUpdate [this next-props _]
    (.log js/console "will upd" (clj->js (om/props this)) (clj->js next-props)))
  (render [this]
    (let [{:keys [x y]} (om/props this)]
      (dom/p nil (str "x: " x "; y: " y)))))

(def child (om/factory Child))

(defui Root
  Object
  (initLocalState [_]
    {:x 0 :y 0 :pressed? false})
  (mouse-down [this e]
    (om/update-state! this assoc :pressed? true)
    (.mouse-move this e))
  (mouse-move [this e]
    (when-let [pressed? (-> this om/get-state :pressed?)]
      (om/update-state! this assoc :x (.-pageX e) :y (.-pageY e))))
  (render [this]
    (let [{:keys [x y]} (om/get-state this)]
      (dom/div #js {:style #js {:height 200
                                :width 600
                                :backgroundColor "red"}
                    :onMouseDown #(.mouse-down this %)
                    :onMouseMove #(.mouse-move this %)
                    :onMouseUp #(om/update-state! this assoc :pressed? false :x 0 :y 0)}
        (child {:x x :y y})))))

(def rec (om/reconciler {:state {}
                         :parser (om/parser {:read read})}))

(defcard test-om-552
  "Test that componentWillUpdate receives updated next-props"
  (dom-node
    (fn [_ node]
      (om/add-root! rec Root node))))

;; ==================
;; OM-543

(def om-543-data
  {:tree {:node/type :tree/foo
          :id 0
          :foo/value "1"
          :children [{:node/type :tree/bar
                      :bar/value "1.1"
                      :id 1
                      :children [{:node/type :tree/bar
                                  :id 2
                                  :bar/value "1.1.1"
                                  :children []}]}
                     {:node/type :tree/foo
                      :id 3
                      :foo/value "1.2"
                      :children []}]}})

(declare item-node)

(defui UnionBarNode
  static om/IQuery
  (query [this]
    '[:id :node/type :bar/value {:children ...}])
  Object
  (render [this]
    (let [{:keys [bar/value children]} (om/props this)]
      (dom/li nil
        (dom/p nil (str "Bar value: " value))
        (dom/ul nil
          (map item-node children))))))

(def bar-node (om/factory UnionBarNode))

(defui UnionFooNode
  static om/IQuery
  (query [this]
    '[:id :node/type :foo/value {:children ...}])
  Object
  (render [this]
    (let [{:keys [foo/value children]} (om/props this)]
      (dom/li nil
        (dom/p nil (str "Foo value: " value))
        (dom/ul nil
          (map item-node children))))))

(def foo-node (om/factory UnionFooNode))

(defui ItemNode
  static om/Ident
  (ident [this {:keys [node/type id]}]
    [type id])
  static om/IQuery
  (query [this]
    {:tree/foo (om/get-query UnionFooNode)
     :tree/bar (om/get-query UnionBarNode)})
  Object
  (render [this]
    (let [{:keys [node/type] :as props} (om/props this)]
      (({:tree/foo foo-node
         :tree/bar bar-node} type)
         props))))

(def item-node (om/factory ItemNode))

(defui UnionTree
  static om/IQuery
  (query [this]
    `[{:tree ~(om/get-query ItemNode)}])
  Object
  (render [this]
    (let [{:keys [tree]} (om/props this)]
      (dom/ul nil
        (item-node tree)))))

(defmulti om-543-read om/dispatch)

(defmethod om-543-read :default
  [{:keys [data] :as env} k _]
  {:value (get data k)})

(defmethod om-543-read :children
  [{:keys [parser data union-query state] :as env} k _]
  (let [st @state
        f #(parser (assoc env :data (get-in st %)) ((first %) union-query))]
    {:value (into [] (map f) (:children data))}))

(defmethod om-543-read :tree
  [{:keys [state parser query ast] :as env} k _]
  (let [st @state
        [type id :as entry] (get st k)
        data (get-in st entry)
        new-env (assoc env :data data :union-query query)]
    {:value (parser new-env (type query))}))

(def om-543-reconciler
  (om/reconciler {:state om-543-data
                  :parser (om/parser {:read om-543-read})}))

(defcard om-543
  "Test that recursive queries in unions work"
  (dom-node
    (fn [_ node]
      (om/add-root! om-543-reconciler UnionTree node))))

;; ==================
;; OM-595

(defmulti om-595-read om/dispatch)

(defmethod om-595-read :item
  [{:keys [state query parser] :as env} key _]
  (let [st @state]
    {:value (om/db->tree query (get st key) st)}))

(def om-595-state
  {:item [:item/by-id 1]
   :item/by-id {1 {:id 1
                   :title "first"
                   :next  [:item/by-id 2]}
                2 {:id 2
                   :title "second"}}})

(def om-595-reconciler
  (om/reconciler {:state  om-595-state
                  :parser (om/parser {:read om-595-read})}))

(defui OM-595-App
  static om/IQuery
  (query [this]
    '[{:item [:id :title {:next ...}]}])
  Object
    (render [this]
      (dom/div nil (str (om/props this)))))

(defcard om-595
  "Test that `index-root` doesn't blow the stack"
  (dom-node
    (fn [_ node]
      (om/add-root! om-595-reconciler OM-595-App node))))

;; ==================
;; OM-601

(defn om-601-read
  [{:keys [state] :as env} k _]
  (let [st @state]
    {:value (get st k)}))

(defn om-601-mutate
  [{:keys [state] :as env} _ _]
  {:action #(swap! state update-in [:foo :foo/val] inc)})

(def om-601-state
  {:foo {:foo/val 0}
   :bar {:bar/val 0}})

(def om-601-reconciler
  (om/reconciler {:state  om-601-state
                  :parser (om/parser {:read om-601-read :mutate om-601-mutate})}))

(defui OM-601-Foo
  static om/IQuery
  (query [this]
    [:foo/val])
  Object
  (render [this]
    (println "Render Foo")
    (dom/div nil
      (dom/p nil (str "Foo: " (:foo/val (om/props this)))))))

(def om-601-foo (om/factory OM-601-Foo))

(defui OM-601-Bar
  static om/IQuery
  (query [this]
    [:bar/val])
  Object
  (render [this]
    (println "Render Bar")
    (dom/div nil
      (dom/p nil (str "Bar: " (:bar/val (om/props this)))))))

(def om-601-bar (om/factory OM-601-Bar))

(defui OM-601-App
  static om/IQuery
  (query [this]
    [{:foo (om/get-query OM-601-Foo)
      :bar (om/get-query OM-601-Bar)}])
  Object
  (render [this]
    (let [{:keys [foo bar]} (om/props this)]
      (dom/div nil
        (om-601-foo foo)
        (om-601-bar bar)
        (dom/button #js {:onClick #(om/transact! this '[(increment/foo!)])}
          "Inc foo")))))

(defcard om-601-card
  "Test that `shouldComponentUpdate` doesn't render unchanged children"
  (dom-node
    (fn [_ node]
      (om/add-root! om-601-reconciler OM-601-App node))))

;; ==================
;; OM-598

(defmulti om-598-read om/dispatch)

(defmethod om-598-read :default
  [{:keys [state]} k _]
  (let [st @state]
    {:value (get st k)}))

(defui OM-598-Child
  static om/IQuery
  (query [this]
   [:bar])
  Object
  (initLocalState [_]
    {:a 1})
  (render [this]
    (let [{:keys [a]} (om/get-state this)]
      (dom/div nil
        (dom/button #js {:onClick #(om/update-state! this update :a inc)}
          (str "a: " a))))))

(def om-598-child (om/factory OM-598-Child))

(defui OM-598-App
  static om/IQuery
  (query [this]
   [:foo])
  Object
  (componentDidMount [this]
    (om/set-query! this {:query [{:child (om/get-query OM-598-Child)}]}))
  (render [this]
    (let [props (om/props this)]
      (dom/div nil
        (when-let [child-props (:child props)]
          (om-598-child child-props))))))

(def om-598-reconciler
  (om/reconciler {:state {:foo 3 :child {:bar 2}}
                  :parser (om/parser {:read om-598-read})}))

(defcard om-598
  "Test that `reindex!` works on query transitions"
  (dom-node
    (fn [_ node]
      (om/add-root! om-598-reconciler OM-598-App node))))

;; ==================
;; OM-641

(declare om-641-reconciler)

(defui OM-641-Item
  static om/Ident
  (ident [this {:keys [id]}]
    [:items/by-id id])
  static om/IQuery
  (query [this] [:id :name])
  Object
  (render [this]
    (let [{:keys [name]} (om/props this)]
      (dom/div nil
        (dom/p nil
          (str "item: " name))
        (dom/p nil
          (str "ref->components "
            (pr-str (-> @(om/get-indexer om-641-reconciler) :ref->components))))))))

(defui OM-641-Root
  static om/IQuery
  (query [this]
    [{:item (om/get-query OM-641-Item)}])
  Object
  (render [this]
    (let [{:keys [item]} (om/props this)]
      (dom/div nil
        ((om/factory OM-641-Item) item)
        (dom/button #js {:onClick #(om/transact! this '[(change/item!)])}
          "change item")))))

(defn om-641-read
  [{:keys [state query]} k params]
  (let [st @state]
    {:value (om/db->tree query (get st k) st)}))

(defn om-641-mutate
  [{:keys [state]} _ _]
  {:action (fn []
             (let [item (get @state :item)]
               (swap! state assoc :item [:items/by-id (mod (inc (second item)) 2)])))})

(def om-641-reconciler
  (om/reconciler {:state (atom {:item [:items/by-id 0]
                                :items/by-id {0 {:id 0 :name "Item 0"}
                                              1 {:id 1 :name "Item 1"}}})
                  :parser (om/parser {:read om-641-read :mutate om-641-mutate})}))

(defcard om-641-card
  (dom-node (fn [_ node]
              (om/add-root! om-641-reconciler OM-641-Root node))))

;; ==================
;; OM-644

(def om-644-app-state (atom {:children []}))

(defn om-644-read [{:keys [state] :as env} key params]
  (let [st @state]
    (if-let [[_ value] (find st key)]
      {:value value}
      {:value :not-found})))

(defn om-644-mutate [{:keys [state] :as env} key params]
  (if (= 'load key)
    {:value {:keys [:count]}
     :action #(swap! state update-in [:children] conj (rand-int 10))}
    {:value :not-found}))

(defui OM-644-Child
  Object
  (render [this]
    (dom/div nil (om/children this))))

(def om-644-child (om/factory OM-644-Child))

(defui OM-644-Root
  static om/IQuery
  (query [this]
    [:children])
  Object
  (render [this]
    (let [{:keys [children]} (om/props this)]
      (om-644-child nil
        (dom/button #js {:onClick #(om/transact! this '[(load)])}
          "Load children")
        (dom/ul nil
          (map #(dom/li nil %) children))))))

(def om-644-reconciler
  (om/reconciler
   {:state om-644-app-state
    :parser (om/parser {:read om-644-read :mutate om-644-mutate})}))

(defcard om-644-card
  (dom-node
    (fn [_ node]
      (om/add-root! om-644-reconciler OM-644-Root node))))

(comment

  (require '[cljs.pprint :as pprint])

  (pprint/pprint @(om/get-indexer reconciler))

  )

(defui ComponentA
  static om/IQuery
  (query [_]
    '[[:current-user _]
      ({:child/props []} {:a :b})]))

(defui ComponentB
  static om/IQuery
  (query [_]
    '[[:current-user _]]))

(defui RootComponent
  static om/IQueryParams
  (params [_]
    {:active-component/query (om/get-query ComponentB)})
  static om/IQuery
  (query [_]
    '[{:com/props ?active-component/query}])

  Object
  (render
    [this]
    (dom/button #js {:onClick #(om/transact! this '[(change/route)])} "swap component")))

(defmulti read (fn [_ k _] k))
(defmethod read :current-user
  [{:keys [parser query target] :as env} _ _]
  (if (nil? target)
    {:value "abc"}
    {:remote true}))

(defmethod read :com/props
  [{:keys [parser query target] :as env} _ _]
  (let [v (parser env query target)]
    (if (nil? target)
      {:value v}
      {target v})))

(defmethod read :child/props
  [{:keys [parser query target ast] :as env} _ _]
  (let [v (parser env query target)]
    (if (nil? target)
      {:value v}
      {:remote (assoc ast :query-root true)} )))

(defn send [_]
  (fn [{:keys [remote]} _]
    (.log js/console "remote" remote)))

(declare my-own-reconciler)
(defn my-mutate
  [{:keys [state] :as env} _ _]
  (let [query (om/get-query ComponentA)]
    {:action
     (om/set-query! my-own-reconciler {:params {:active-component/query query}})}))

(def my-own-reconciler
  (om/reconciler
    {:state (atom {})
     :parser (om/parser {:read read
                         :mutate my-mutate
                         :send (send "http://nothing.com")
                         })}))

(defcard my-card
         (dom-node
           (fn [_ node]
             (om/add-root! my-own-reconciler RootComponent node))))
