(ns #^{:author "Roger Allen"
       :doc "Overtone library code."}
  shadertone.tone
  (:use [overtone.helpers lib]
        [overtone.libs event deps]
        [overtone.sc defaults synth ugens buffer node foundation-groups bus]
        [overtone.sc.machinery.server connection comms native]
        [overtone.sc.cgens buf-io tap]
        [overtone.studio core util]
        )
  (:require [shadertone.shader :as s]
            [overtone.sc.server :as server]) ;; has a "stop" in it
  (:import (org.lwjgl BufferUtils)
           (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (org.lwjgl.opengl GL11 GL12 GL13 GL20 ARBTextureRg)))

           
;; ----------------------------------------------------------------------
;; Tap into the Overtone output volume and send it to iOvertoneVolume
;; volume tap synth inspired by
;;   https://github.com/samaaron/arnold/blob/master/src/arnold/voltap.clj
(defsynth vol []
  (tap "system-vol" 60 (lag (abs (in:ar 0)) 0.1)))

(defonce voltap-synth
  (vol [:after (foundation-monitor-group)]))

;; ----------------------------------------------------------------------
;; Grab Waveform & FFT data and send it to the iChannel[0] texture.
;; data capture fns cribbed from overtone/gui/scope.clj
(defonce WAVE-BUF-SIZE 4096) ; stick to powers of 2 for fft and GL
(defonce WAVE-BUF-SIZE-2X (* 2 WAVE-BUF-SIZE))
(defonce FFTWAVE-BUF-SIZE (* 2 WAVE-BUF-SIZE))
(defonce init-wave-array (float-array (repeat WAVE-BUF-SIZE 0.0)))
(defonce init-fft-array (float-array (repeat WAVE-BUF-SIZE 0.0)))
;; synths pour data into these bufs
(defonce wave-buf (buffer WAVE-BUF-SIZE))
(defonce fft-buf (buffer WAVE-BUF-SIZE))

;; on request from ogl, stuff wave-buf & fft-buf into fftwave-float-buf
;; and use that FloatBuffer for texturing
(defonce fftwave-tex-id (atom 0))
(defonce fftwave-tex-num (atom 0))
(defonce fftwave-float-buf (-> ^FloatBuffer (BufferUtils/createFloatBuffer FFTWAVE-BUF-SIZE)
                               (.put ^floats init-fft-array)
                               (.put ^floats init-wave-array)
                               (.flip)))

                               
(defonce wave-bus-synth (bus->buf [:after (foundation-monitor-group)] 0 wave-buf))

(defn- ensure-internal-server!
  "Throws an exception if the server isn't internal - wave relies on
  fast access to shared buffers with the server which is currently only
  available with the internal server. Also ensures server is connected."
  []
  (when (server/server-disconnected?)
    (throw (Exception. "Cannot use waves until a server has been booted or connected")))
  (when (server/external-server?)
    (throw (Exception. (str "Sorry, it's only possible to use waves with an internal server. Your server connection info is as follows: " (server/connection-info))))))

;; Inspired by overtone/gui/scope.clj, but made changes for
;; - linear rather than logarithmic dB output
;; - by hand magnitude calculation rather than pv_magsmear
;; - internal fft-buf 2x external buf since it contains
;;   real/imag pairs.
;;
;; Buffer update speed:
;;     N  sec/buf hz/buf  res @44100Hz
;;     512 0.012   86.1  43.1
;;    1024 0.023   43.1  21.5
;;    2048 0.046   21.5  10.8
;;    4096 0.093   10.8   5.4 <<< seems reasonable compromise
;;    8192 0.186    5.4   2.7
;;   16384 0.372    2.7   1.3
;;   32768 0.743    1.3   0.7
;;
;; http://www.physik.uni-wuerzburg.de/~praktiku/Anleitung/Fremde/ANO14.pdf
(defsynth bus-freqs->buf
  [in-bus 0 scope-buf 1 fft-buf-size WAVE-BUF-SIZE-2X rate 2]
  (let [phase     (- 1 (* rate (reciprocal fft-buf-size)))
        fft-buf   (local-buf fft-buf-size 1)
        ;; drop DC & nyquist samples
        n-samples (* 0.5 (- (buf-samples:ir fft-buf) 2))
        signal    (in in-bus 1)
        ;; found 0.5 window gave less periodic noise
        freqs     (fft fft-buf signal 0.5 HANN)
        ;; indexer = 2, 4, 6, ..., N-4, N-2
        indexer   (+ n-samples 2
                     (* (lf-saw (/ rate (buf-dur:ir fft-buf)) phase) ;; what are limits to this rate?
                        n-samples))
        indexer   (round indexer 2) ;; always point to the real sample
        ;; convert real,imag pairs to magnitude
        s0        (buf-rd 1 fft-buf indexer 1 1)
        s1        (buf-rd 1 fft-buf (+ 1 indexer) 1 1) ; kibit keep
        lin-mag   (sqrt (+ (* s0 s0) (* s1 s1)))]
    (record-buf lin-mag scope-buf)))

(defonce fft-bus-synth
  (bus-freqs->buf [:after (foundation-monitor-group)] 0 fft-buf))

;; user-fn for shader display of waveform and fft
(defn tone-fftwave-fn
  "The shader display will call this routine on every draw.  Update
  the waveform texture with FFT data in the first row and waveform
  data in the 2nd row."
  [dispatch pgm-id tex-id-i]
  (case dispatch ;; FIXME defmulti?
    :init ;; create & bind the texture
    (let [tex-id tex-id-i]
      (ensure-internal-server!)
      (reset! fftwave-tex-id tex-id)
      (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id)
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MAG_FILTER
                            GL11/GL_LINEAR)
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_MIN_FILTER
                            GL11/GL_LINEAR)
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_S
                            GL12/GL_CLAMP_TO_EDGE)
      (GL11/glTexParameteri GL11/GL_TEXTURE_2D GL11/GL_TEXTURE_WRAP_T
                            GL12/GL_CLAMP_TO_EDGE)
      (GL11/glTexImage2D GL11/GL_TEXTURE_2D
                         0 ARBTextureRg/GL_R32F
                         ^Integer WAVE-BUF-SIZE
                         2 0 GL11/GL_RED GL11/GL_FLOAT
                         ^FloatBuffer fftwave-float-buf)
      (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
      (println "init fftwave " WAVE-BUF-SIZE))
    :pre-draw ;; grab the data and put it in the texture for drawing.
    (let [fbbp (buffer-data fft-buf)
          wbb (buffer-data wave-buf)
          ]
    (do
      (if (buffer-live? wave-buf) ;; FIXME? assume fft-buf is live
        (-> ^FloatBuffer fftwave-float-buf
            (.put ^floats fbbp) ;(.put ^floats (buffer-data fft-buf)
            (.put ^floats wbb) ;(.put ^floats (buffer-data wave-buf)
            (.flip))
            )
      (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-i))
      (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id-i)
      (GL11/glTexImage2D GL11/GL_TEXTURE_2D 0 ARBTextureRg/GL_R32F
                         ^Integer WAVE-BUF-SIZE
                         2 0 GL11/GL_RED GL11/GL_FLOAT
                         ^FloatBuffer fftwave-float-buf)
    ;(println "pre-draw fftwave " tex-id-i )
    )
    )
    :post-draw ;; unbind the texture
    (do
      (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-i))
      (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
      )
    :destroy ;;
    (do
      (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
      (GL11/glDeleteTextures ^Integer tex-id-i))))

(defn- fix-fftwav-texture
  "look for the :overtone-audio keyword, set the fftwave-tex-num atom"
  [[i tex]]
  (if (or (string? tex)
          (and (keyword? tex) (= :previous-frame tex)))
    tex ;; just return the string untouched
    (do
      (assert (keyword? tex))
      (assert (= :overtone-audio tex))
      (reset! fftwave-tex-num i) ;; NOTE: multiple entries will only use last one
      nil))) ;; return nil

(defn- fix-texture-list
  "look for the :overtone-audio keyword and replace it with nil"
  [textures]
  (reset! fftwave-tex-num 0) ;; FIXME this default could mess up other tex0 cases
  (map fix-fftwav-texture (map-indexed vector textures)))

  
;;Camera and video controls

(defn post-start-cam [cam-id] (if (= true (integer? cam-id)) (s/post-start-cam cam-id)))

(defn post-start-video [video-filename video-id] (if (= true (integer? video-id))  (s/post-start-video video-filename video-id)))

(defn release-cam-textures [cam-id](if (= true (integer? cam-id))(s/release-cam-textures cam-id)))

(defn release-video-textures [video-id] (if (= true (integer? video-id)) (s/release-video-textures video-id)))

(defn set-video-frame [video-id frame]  (if (= true (integer? video-id)) (s/set-video-frame video-id frame)))

(defn set-video-frame-limits [video-id min max] (if (= true (integer? video-id)) (s/set-video-frame-limits video-id min max)))

(defn set-video-fps [video-id new-fps] (if (= true (integer? video-id)) (s/set-video-fps video-id  new-fps)))

(defn set-video-play [video-id](if (= true (integer? video-id)) (s/set-video-play video-id)))

(defn set-video-pause [video-id](if (= true (integer? video-id)) (s/set-video-pause video-id)))

(defn set-video-reverse [video-id](if (= true (integer? video-id)) (s/set-video-reverse video-id)))

(defn bufferSection [video-id active_buffer_idx begin-frame] (try (s/bufferSection video-id active_buffer_idx begin-frame)
                                                                (catch Exception e (str "caught exception: " (.getMessage e)))))

(defn set-fixed-buffer-index    ([video-id mode] (s/set-fixed-buffer-index video-id mode))
                                ([video-id mode frame] (s/set-fixed-buffer-index video-id mode frame)))
                                
(defn set-video-fixed [video-id mode] (s/set-video-fixed video-id mode))

(defn set-active-buffer-video [video-id newIdx] (s/setActiveBuffer video-id newIdx))

(defn record-cam [cam-id buffer_idx] (s/record-cam cam-id buffer_idx))

(defn set-fixed-cam [cam-id mode] (s/set-fixed-cam cam-id mode))

(defn set-active-buffer-cam [video-id newIdx](s/set-active-buffer-cam video-id newIdx))

(defn set-fixed-buffer-index-cam ([cam-id mode] (s/set-fixed-buffer-index-cam cam-id mode) )
                                 ([cam-id mode frame](s/set-fixed-buffer-index-cam cam-id mode frame)))

(defn set-dataArray-item [idx val](if (= true (and (integer? idx) (< val 256))) (s/set-dataArray-item idx val)))

(defn getWindowState [] (s/getWindowState))

(defn get-video-histogram [video-id color] (let [ws   (getWindowState)]
                                                (case color 
                                                        :red (nth (:redHistogram-video @ws) video-id)
                                                        :green (nth (:greenHistogram-video @ws) video-id)
                                                        :blue (nth (:blueHistogram-video @ws) video-id)
                                                        )))

(defn get-cam-histogram [cam-id color] (let [ws   (getWindowState)]
                                                (case color 
                                                        :red (nth (:redHistogram-cam @ws) cam-id)
                                                        :green (nth (:greenHistogram-cam @ws) cam-id)
                                                        :blue (nth (:blueHistogram-cam @ws) cam-id)
                                                        )))

(defn toggle-analysis [video-id isVideo method] (if (= true (integer? video-id)) (s/toggle-analysis video-id isVideo method)))

(defn toggle-recording [device] (s/toggle-recording device))

(defn write-text [text x y size r g b thickness linetype clear](s/write-text text x y size r g b thickness linetype clear))

;; ======================================================================
(defonce tone-user-data (atom {}))
(defonce tone-user-locs (atom {}))

;; The default Overtone user-draw-fn expects:
;;   uniform float iOvertoneVolume;
;; at the top of your glsl shader.
;; Also calls tone-fftwave-fn to put waveform and fft data into:
;;   iFftWave
(defn- tone-default-fn
  [dispatch pgm-id tex-id-i]
  (case dispatch ;; FIXME defmulti?
    :init ;; find Uniform Location
    (doseq [key (keys @tone-user-data)]
      (let [;_ (println " key " key)
            loc (GL20/glGetUniformLocation ^Integer pgm-id ^String key)]
        (swap! tone-user-locs assoc key loc)))
    :pre-draw
    (doseq [key (keys @tone-user-data)]
      (let [loc (@tone-user-locs key)
            val (deref (@tone-user-data key))
            val (if (map? val)
                  ;; special handling for maps--expected to be synth taps
                  ;; FIXME?  is a map okay? or should it be a record?
                  (try
                    (float @(get-in (:synth val) [:taps (:tap val)]))
                    (catch Exception e 0.0))
                  ;; other user data
                  val)]
        ;;(println key loc val)
        (if (float? val)
          (GL20/glUniform1f loc val)
          (when (vector? val)
            (case (count val)
              1 (GL20/glUniform1f loc (nth val 0))
              2 (GL20/glUniform2f loc (nth val 0) (nth val 1))
              3 (GL20/glUniform3f loc (nth val 0) (nth val 1) (nth val 2))
              4 (GL20/glUniform4f loc (nth val 0) (nth val 1) (nth val 2) (nth val 3)))))))
    :post-draw
    nil ;; nothing to do
    :destroy
    nil ;; nothing to do
    )
  ;(tone-fftwave-fn dispatch pgm-id tex-id-i)
  )

;; ======================================================================
;; Public A1PI
(defn start
  "Start a new shader display.  Pass in optional user-data and user-fn
  for custom control."
  [shader-filename-or-str-atom
   &{:keys [width height title textures cams videos user-data user-fn]
     :or {width      600
          height     600
          title      "shadertone"
          textures   []
          cams       []
          videos     []
          user-data  {}
          user-fn    tone-default-fn
          }}]
  (let [_ (println "start")
        ;textures (fix-texture-list textures)
        user-data (merge-with #(or %1 %2) ; kibit keep
                              user-data {"iOvertoneVolume"
                                         (atom {:synth voltap-synth
                                                :tap   "system-vol"})})
                                                ]
    (reset! tone-user-data user-data)
    (s/start shader-filename-or-str-atom
             :width      width
             :height     height
             :title      title
             :textures   textures
             :cams       cams
             :videos     videos
             :user-fn    user-fn
             )))

(defn start-fullscreen
  "Start a new fullscreen shader display.  Pass in optional user-data
  and user-fn for custom control."
  [shader-filename-or-str-atom
   &{:keys [textures cams videos user-data user-fn]
     :or {textures   []
          cams       []
          videos     []
          user-data  {}
          user-fn    tone-default-fn
          }}]
  (let [;textures (fix-texture-list textures)
        user-data (merge-with #(or %1 %2) ; kibit keep
                              user-data {"iOvertoneVolume"
                                         (atom {:synth voltap-synth
                                                :tap   "system-vol"})})]
    (reset! tone-user-data user-data)
    (s/start-fullscreen shader-filename-or-str-atom
                        :textures   textures
                        :cams       cams
                        :videos     videos
                        :user-fn    user-fn
                        )))

(defn stop
  []
  (s/stop))
