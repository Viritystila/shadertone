(defproject shadertone "0.2.6-SNAPSHOT"
  :description "A clojure library designed to mix musical synthesis via Overtone and dynamic visuals a la www.shadertoy.com"
  :url "http://github.com/overtone/shadertone"
  :license {:name "MIT License"
           :url "https://github.com/overtone/shadertone/blob/master/LICENSE"}
  :injections [ (clojure.lang.RT/loadLibrary org.opencv.core.Core/NATIVE_LIBRARY_NAME)]
  :repositories [["Viritystila" "https://github.com/Viritystila/OpenCV/raw/master"]]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [org.clojure/core.async "0.4.474"]
                 [while-let "0.2.0"]
                 [hello_lwjgl/lwjgl   "2.9.1"]
                 [overtone            "0.10.3"]
                 [watchtower          "0.1.1"]
                 [opencv/opencv "4.0.1-linux"]
                 [opencv/opencv-native "4.0.1-linux"]
                 [org.bytedeco/javacpp "1.4.5-SNAPSHOT"]
                 [org.bytedeco.javacpp-presets/v4l2 "Latest-1.4.5-SNAPSHOT"]
                 [org.bytedeco.javacpp-presets/v4l2-platform "Latest-1.4.5-SNAPSHOT"]
                 ]

  :main ^{:skip-aot true} shadertone.core
  :jvm-opts ^:replace [] 
  )
