(ns build-facts.util.env)

(defn env-var [name]
  (System/getenv name))
