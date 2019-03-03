;LWJGL3 from from https://github.com/rogerallen/hello_lwjgl/blob/master/project.clj
(require 'leiningen.core.eval)

;; per-os jvm-opts code cribbed from Overtone
(def JVM-OPTS
  {:common   []
   :macosx   ["-XstartOnFirstThread" "-Djava.awt.headless=true"]
   :linux    []
   :windows  []})

(defn jvm-opts
  "Return a complete vector of jvm-opts for the current os."
  [] (let [os (leiningen.core.eval/get-os)]
       (vec (set (concat (get JVM-OPTS :common)
                         (get JVM-OPTS os))))))
(def LWJGL_NS "org.lwjgl")
(def LWJGL_VERSION "3.2.1")
;; Edit this to add/remove packages.
(def LWJGL_MODULES ["lwjgl"
                    "lwjgl-glfw"
                    "lwjgl-opengl"
])

(def LWJGL_PLATFORMS ["linux" "macos" "windows"])
;; These packages don't have any associated native ones.
(def no-natives? #{"lwjgl-egl" "lwjgl-jawt" "lwjgl-odbc"
"lwjgl-opencl" "lwjgl-vulkan"})
(defn lwjgl-deps-with-natives []
  (apply concat
         (for [m LWJGL_MODULES]
           (let [prefix [(symbol LWJGL_NS m) LWJGL_VERSION]]
             (into [prefix]
                   (if (no-natives? m)
                     []
                     (for [p LWJGL_PLATFORMS]
                       (into prefix [:classifier (str "natives-" p) :native-prefix ""]))))))))

(def all-dependencies
  (into ;; Add your non-LWJGL dependencies here
   '[           [org.clojure/clojure "1.9.0"]
                [org.clojure/tools.namespace "0.2.11"]
                [org.clojure/core.async "0.4.490"]
                [while-let "0.2.0"]
                [overtone            "0.10.3"]
                [watchtower          "0.1.1"]
                [org.bytedeco.javacpp-presets/opencv-platform "4.0.1-1.4.5-SNAPSHOT"]
                [org.bytedeco.javacpp-presets/opencv "4.0.1-1.4.5-SNAPSHOT" :classifier "linux-x86_64"]
                ;[org.viritystila/opencv "4.0.1-linux"]
                ;[org.viritystila/opencv-native "4.0.1-linux"]
                [org.bytedeco/javacpp "1.4.5-SNAPSHOT"]
                [org.viritystila/v4l2 "Latest-1.4.5-SNAPSHOT"]
                [org.viritystila/v4l2-platform "Latest-1.4.5-SNAPSHOT"]]
(lwjgl-deps-with-natives)))


(defproject org.viritystila/viritystone "0.0.1-SNAPSHOT"
  :description "A evolution of the Shadertone, a clojure library designed to mix musical synthesis via Overtone and dynamic visuals a la www.shadertoy.com"
  :url "https://github.com/Viritystila/shadertone"
  :license {:name "MIT License"
           :url "https://github.com/Viritystila/shadertone/blob/master/LICENSE"}
  ;:injections [ (clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)]
  :repositories [["Viritystila" "https://github.com/Viritystila/OpenCV/raw/master"]]
  :dependencies ~all-dependencies
  :java-source-paths ["src/java"]
  :main ^{:skip-aot true} viritystone.core
  :jvm-opts ^:replace ~(jvm-opts)
  )
