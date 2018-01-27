(defproject shadertone "0.2.6-SNAPSHOT"
  :description "A clojure library designed to mix musical synthesis via Overtone and dynamic visuals a la www.shadertoy.com"
  :url "http://github.com/overtone/shadertone"
  :license {:name "MIT License"
           :url "https://github.com/overtone/shadertone/blob/master/LICENSE"}
  ;:injections [(nu.pattern.OpenCV/loadShared)
  ;            (clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)]
  :dependencies [;; 1.6.0 causes error with *warn-on-reflection*.  1.7.0-RC1 works
                 [org.clojure/clojure "1.8.0"]
                 [hello_lwjgl/lwjgl   "2.9.1"]
                 [overtone            "0.10.3"]
                 [watchtower          "0.1.1"]
                 ;[org.openpnp/opencv "3.2.0-1"]
                 [vision  "1.0.0-SNAPSHOT"]]
    :jvm-opts ["-Djna.library.path=../vision/resources/lib" "-XX:MaxDirectMemorySize=6G" "-Xmx6g"]

  :main ^{:skip-aot true} shadertone.core
  ;; add per WARNING: JVM argument TieredStopAtLevel=1 is active...

  ;; add leipzig for use in examples
  :profiles {:dev {:dependencies [[leipzig "0.6.0" :exclusions [[overtone]]]]
                   ;; enabling this outputs a lot of spew. disable for normal runs
                   ;; :global-vars {*warn-on-reflection* true *assert* false}
                   }})
