(ns plasma.peer-test
  (:use [plasma config util core connection peer]
        [jiraph graph]
        test-utils
        clojure.test
        clojure.stacktrace)
  (:require [logjam.core :as log]
            [lamina.core :as lamina]
            [plasma.query :as q]))

;(log/file :peer "peer.log")
;(log/repl :peer)

(deftest ping-test
  (let [p (peer "db/p1" {:port 1234})
        manager (:manager p)]
    (try
      (let [client (get-connection manager (plasma-url "localhost" 1234))]
        (dotimes [i 20]
          (let [res-chan (request client 'ping nil)
                res (lamina/wait-for-message res-chan 100)]
            (is (= :pong (:result res)))))
        (close client))
      (finally
        (close p)))))

(defn- reset-peer
  [p]
  (with-graph (:graph p)
    (clear-graph)
    (test-graph)))

(deftest peer-query-test []
  (dosync (alter config* assoc
                 :peer-port (+ 10000 (rand-int 20000))
                 :presence-port (+ 10000 (rand-int 20000))))
  (let [port (+ 1000 (rand-int 10000))
        local (peer "db/p1" {:port port})
        manager (:manager local)]
    (try
      (reset-peer local)
      (let [con (get-connection manager (plasma-url "localhost" port))]
        (is (= :pong (:result (lamina/wait-for-message (request con 'ping []) 100))))
        (is (uuid? (:id (peer-node con ROOT-ID 1000))))
        (let [q (q/path [synth [:music :synths :synth]]
                  (where (>= (:score synth) 0.6)))
              lres (query local q {} 100)
              res (peer-query con q 100)
              chan-res (take 2 
                             (lamina/lazy-channel-seq
                               (query-channel local q) 100))]
          (is (= lres res chan-res))
          (is (= 2 (count res)))
          (is (= #{:bass :kick} (set (map :label (map #(peer-node con % 1000) res)))))))
      (finally
        (close local)))))

(deftest proxy-node-test []
  (dosync (alter config* assoc
                 :peer-port (+ 10000 (rand-int 20000))
                 :presence-port (+ 10000 (rand-int 20000))))
  (let [port     (+ 1000 (rand-int 10000))
        manager  (connection-manager)
        local    (peer "db/p1" {:port port :manager manager})
        remote   (peer "db/p2" {:port (inc port) :manager manager})
        remote-p (get-connection manager (plasma-url "localhost" (inc port)))]
    (try
      (reset-peer local)
      (reset-peer remote)
      ; Add a proxy node to the local graph pointing to the root of the remote
      ; graph.
      (let [remote-root (peer-node remote-p ROOT-ID 100)
            _ (log/to :peer "remote-root: " remote-root)
            net (first (query local (q/path [:net]) {} 100))
            _ (log/to :peer "net: " net)
            peer-proxy (with-graph (:graph local)
                        (proxy-node (:id remote-root)
                                    (plasma-url "localhost" (inc port))))
            link (with-graph (:graph local)
                   (edge net peer-proxy :label :peer))]

        ; Now issue a query that will traverse over the network
        ; through the proxy node.
        (let [q (-> (q/path [synth [:net :peer :music :synths :synth]])
                  (q/project 'synth :label))
              res (take 4 (query local q {} 100))]
          (is (= #{:kick :bass :snare :hat}
                 (set (map :label res))))
          (comment println "res: " res)))
      (finally
        (close local)
        (close remote)
        (clear-connections manager)))))

(deftest many-proxy-node-test []
  (dosync (alter config* assoc
                 :peer-port (+ 10000 (rand-int 20000))
                 :presence-port (+ 10000 (rand-int 20000))))
  (let [n-peers 10
        port (+ 1000 (rand-int 10000))
        local (peer "db/p1" {:port port})
        peers (doall
                (map
                  (fn [n]
                    (let [p (peer (str "db/peer-" n)
                                  {:port (+ port n 1)
                                   :manager (:manager local)})]
                      (with-graph (:graph p)
                        (clear-graph)
                        (let [root-id (root-node)]
                          (node-assoc root-id :peer-id n)
                          (with-nodes! [net :net
                                        docs :docs
                                        a {:label (str "a-" n) :score 0.1}
                                        b {:label (str "b-" n) :score 0.5}
                                        c {:label (str "c-" n) :score 0.9}]
                            (edge root-id net :label :net)
                            (edge root-id docs :label :docs)
                            (edge docs a :label :doc)
                            (edge docs b :label :doc)
                            (edge docs c :label :doc))
                          [p root-id n]))))
                  (range n-peers)))]
    (try
      (with-graph (:graph local)
        (clear-graph)
        (let [root-id (root-node)
              net (node :label :net)]
          (edge root-id net :label :net)
          (doseq [[p peer-root n] peers]
            (edge net
                  (proxy-node peer-root (plasma-url "localhost" (+ port n 1)))
                  :label :peer))
         ; (println "peers: " (query (path [:net :peer])))
          ))
        (let [q (-> (q/path [doc [:net :peer :docs :doc]]
                      (where (> (:score doc) 0.5)))
                  (q/project 'doc :label :score))
              res (query local q {} 100)]
          ;(println "res: " res)
          (is (= n-peers (count res))))
      (finally
        (close local)
        (close-peers (map first peers))))))

(defn node-chain
  "Create a chain of n nodes starting from src, each one connected
  by an edge labeled label.  Returns the id of the last node in the
  chain."
  [src n label]
  (let [chain-ids (doall
                    (take (inc n) (iterate
                              (fn [src-id]
                                (let [n (node)]
                                  (edge src-id n :label label)
                                  n))
                              src)))]
    (log/to :peer "----------------------\n"
            "chain-ids: " (seq chain-ids))
    (last chain-ids)))

(deftest iter-n-test
  (let [local (peer "db/p1")]
    (try
      (let [end-id (with-graph
                     (:graph local)
                     (clear-graph)
                     (let [root-id (root-node)]
                       (log/to :peer "root-id: " root-id)
                       (node-chain root-id 10 :foo)))
            res-chan (iter-n-query local 10 (q/path [:foo]))]
          (is (= end-id
                 (first (lamina/channel-seq res-chan 200)))))
      (finally
        (close local)))))

(deftest connect-test
  (let [local (peer "db/p1" {:port 2342})
        connected (atom [])]
    (try
      (on-connect local (fn [new-con] (swap! connected #(conj % (:url new-con)))))
      (dotimes [i 10]
        (let [con (get-connection (connection-manager) (plasma-url "localhost" 2342))]
          (peer-node con ROOT-ID 20)
          (close con)))
      ;(println "connected: " @connected)
      (is (= 10 (count @connected)))
      (finally 
        (close local)))))

(deftest connection-handler-test
  (let [p1 (peer "db/p1" {:port 2222})
        p2 (peer "db/p2" {:port 3333})
        res (atom [])]
    (try
      (on-connect (:listener p2) (fn [incoming]
                                   (future 
                                     (let [root (peer-node incoming ROOT-ID 100)]
                                       (swap! res #(conj % [1 (:id root)]))))))
      (on-connect (:listener p2) (fn [incoming]
                                   (future 
                                     (let [root (peer-node incoming ROOT-ID 100)]
                                       (swap! res #(conj % [2 (:id root)]))))))
      (let [con (get-connection (connection-manager) (plasma-url "localhost" 3333))]
        (handle-peer-connection p1 con)
        (Thread/sleep 100)
        (let [id (:id (node-by-uuid p1 ROOT-ID))
              sres (sort-by first @res)]
          (is (= [1 id] (first sres)))
          (is (= [2 id] (second sres)))))
        (finally 
          (close p1)
          (close p2)))))

