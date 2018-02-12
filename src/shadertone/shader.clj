(ns #^{:author "Roger Allen"
       :doc "Shadertoy-like core library."}
  shadertone.shader
  (:require [watchtower.core :as watcher]
            [clojure.java.io :as io]
            clojure.string)
  (:import [org.opencv.core Mat Core CvType]
    [org.opencv.videoio Videoio VideoCapture]
    [org.opencv.video Video]
           (java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster)
           (java.io File FileInputStream)
           (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (java.util Calendar)
           (javax.imageio ImageIO)
           (java.lang.reflect Field)
           (org.lwjgl BufferUtils)
           (org.lwjgl.input Mouse)
           (org.lwjgl.opengl ContextAttribs Display DisplayMode
                             GL11 GL12 GL13 GL15 GL20
                             PixelFormat SharedDrawable)))
;; ======================================================================
;; State Variables
;; a map of state variables for use in the gl thread
(defonce default-state-values
  {:active              :no  ;; :yes/:stopping/:no
   :width               0
   :height              0
   :title               ""
   :display-sync-hz     60
   :start-time          0
   :last-time           0
   ;; mouse
   :mouse-clicked       false
   :mouse-pos-x         0
   :mouse-pos-y         0
   :mouse-ori-x         0
   :mouse-ori-y         0
   ;; geom ids
   :vbo-id              0
   :vertices-count      0
   ;; shader program
   :shader-good         true ;; false in error condition
   :shader-filename     nil
   :shader-str-atom     (atom nil)
   :shader-str          ""
   :vs-id               0
   :fs-id               0
   :pgm-id              0
   ;; shader uniforms
   :i-resolution-loc    0
   :i-global-time-loc   0
   :i-channel-time-loc  0
   :i-mouse-loc         0
   :i-channel-loc       [0 0 0 0]
   ;V4l2 feeds
   :i-cam-loc           [0 0 0 0 0]
   :running-cam         [false false false false false]
   :capture-cam         [0 0 0 0 0]
   :buffer-cam          [0 0 0 0 0]
   :target-cam          [0 0 0 0 0]
   :text-id-cam         [0 0 0 0 0]
   :image-bytes-cam     [0 0 0 0 0]
   :nbytes-cam          [0 0 0 0 0]
   :internal-format-cam [0 0 0 0 0]
   :format-cam          [0 0 0 0 0]
   :fps-cam             [0 0 0 0 0]
   :width-cam           [0 0 0 0 0]
   :height-cam          [0 0 0 0 0]
   ;Video feeds
   :i-video-loc         [0 0 0 0 0]
   :running-video       [false false false false false]
   :video-no-id         [nil nil nil nil nil]
   :capture-video       [nil nil nil nil nil]
   :buffer-video-frame  [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
   :target-video        [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
   :text-id-video       [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
   :internal-format-video [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
   :format-video        [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
   :fps-video           [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
   :width-video         [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
   :height-video        [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
   :frames-video        [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
   :frame-ctr-video     [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
   :frame-change-video  [(atom false) (atom false) (atom false) (atom false) (atom false)]
   :frame-start-video   [(atom 1) (atom 1) (atom 1) (atom 1) (atom 1)]
   :frame-stop-video    [(atom 2) (atom 2) (atom 2) (atom 2) (atom 2)]

   :is-paused-video     [false false false false false]
   ;Other
   :tex-id-fftwave      0
   :i-fftwave-loc      [0]
   :i-channel-res-loc   0
   :i-date-loc          0
   :channel-time-buffer (-> (BufferUtils/createFloatBuffer 4)
                            (.put (float-array
                                   [0.0 0.0 0.0 0.0]))
                            (.flip))
   :channel-res-buffer (-> (BufferUtils/createFloatBuffer (* 3 12))
                            (.put (float-array
                                   [0.0 0.0 0.0
                                    0.0 0.0 0.0
                                    0.0 0.0 0.0
                                    0.0 0.0 0.0]))
                            (.flip))
   ;; textures
   :tex-filenames       []
   :tex-no-id           [nil nil nil nil nil]
   :tex-ids             []
   :cams                []
   :videos              []
   :tex-types           [] ; :cubemap, :previous-frame
   ;; a user draw function
   :user-fn             nil
   ;; pixel read
   :pixel-read-enable   false
   :pixel-read-pos-x    0
   :pixel-read-pos-y    0
   :pixel-read-data      (-> (BufferUtils/createByteBuffer 3)
                            (.put (byte-array (map byte [0 0 0])))
                            (.flip))
   })

;; GLOBAL STATE ATOMS
;; Tried to get rid of this atom, but LWJGL is limited to only
;; one window.  So, we just keep a single atom containing the
;; current window state here.
(defonce the-window-state (atom default-state-values))
;; The reload-shader atom communicates across the gl & watcher threads
(defonce reload-shader (atom false))
(defonce reload-shader-str (atom ""))
;; Atom for the directory watcher future
(defonce watcher-future (atom (future (fn [] nil))))
;; Flag to help avoid reloading shader right after loading it for the
;; first time.
(defonce watcher-just-started (atom true))
(defonce throw-on-gl-error (atom true))
;;
(defonce pixel-value (atom [0.0 0.0 0.0]))
;SharedDrawable
(def sharedD 0)

; Number of textures
(def no-textures 4)
;Number of V4l2 -feeds
(def no-cams 5)
;Number of video -feeds
(def no-videos 5)

;;;;;;;;;;;;;;;;;;;;;;;
;;General use functions
;;;;;;;;;;;;;;;;;;;;;;;
(def not-nil? (complement nil?)) 

(defn sleepTime
    [startTime endTime fps] 
    (let [  dtns    (- endTime startTime)
            dtms    (* dtns 1e-6)
            fpdel   (/ 1 fps)
            fpdelms (* 1e3 fpdel)
            dt      (- fpdelms dtms)
            dtout  (if (< dt 0)  0  dt)]
            dtout))    
 
(defn- set-nil [coll pos] (assoc coll pos nil)) 
;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;
;;OPENCV 3 functions
;;;;;;;;;;;;;;;;;;;;
(defn oc-capture-from-cam [cam-id] (let [           vc (new org.opencv.videoio.VideoCapture) 
                                                    vco (try (.open vc cam-id) (catch Exception e (str "caught exception: " (.getMessage e))))]
                                                    vc))

(defn oc-capture-from-video [video-filename] (let [ vc (new org.opencv.videoio.VideoCapture) 
                                                    vco (try (.open vc video-filename) (catch Exception e (str "caught exception: " (.getMessage e))))]
                                                    vc))


(defn oc-release [capture] (.release capture))

(defn oc-query-frame [capture buffer] (.read capture buffer))

(defn oc-set-capture-property [dispatch capture val](case dispatch 
                                              :pos-msec
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_POS_MSEC  val)          

                                              :pos-frames
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_POS_FRAMES   val)          

                                              :pos-avi-ratio
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_POS_AVI_RATIO  val)          
                                                                                            
                                              :frame-width
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_WIDTH  val)          
                                              
                                              :frame-height
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_HEIGHT  val)          
                                              
                                              :fps
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_FPS  val)          
                                              
                                              :fourcc
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_FOURCC   val)          
                                              
                                              :frame-count
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_COUNT  val)    

                                              :format
                                              (.set capture org.opencv.videoio.Videoio/CV_CAP_PROP_FORMAT)
                                              
                                              :brightness
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_BRIGHTNESS   val)          
                                              
                                              :contrast
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_CONTRAST   val)          
                                              
                                              :saturation
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_SATURATION   val)          
                                              
                                              :hue
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_HUE   val)          
                                              
:default (throw (Exception. "Unknown Property.")))
)

(defn oc-get-capture-property [dispatch capture](case dispatch
                                              :pos-msec
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_POS_MSEC)          

                                              :pos-frames
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_POS_FRAMES)          

                                              :pos-avi-ratio
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_POS_AVI_RATIO)          
                                                                                            
                                              :frame-width
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_WIDTH)          
                                              
                                              :frame-height
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_HEIGHT)          
                                              
                                              :fps
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_FPS)          
                                              
                                              :fourcc
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_FOURCC)          
                                              
                                              :frame-count
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_FRAME_COUNT)     
                                              
                                              :format
                                              (.get capture org.opencv.videoio.Videoio/CV_CAP_PROP_FORMAT)
                                              
                                              :brightness
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_BRIGHTNESS)          
                                              
                                              :contrast
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_CONTRAST)          
                                              
                                              :saturation
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_SATURATION)          
                                              
                                              :hue
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_HUE)          
                                              
:default (throw (Exception. "Unknown Property.")))
)

(defn oc-mat-to-bytebuffer [mat] (let [height      (.height mat)
                                       width       (.width mat)
                                       channels    (.channels mat)
                                       size        (* height width channels)
                                       data        (byte-array size)
                                       _           (.get mat 0 0 data)
                                       
] ^ByteBuffer (-> (BufferUtils/createByteBuffer size)
                                              (.put data)
                                              (.flip))))


(defn oc-new-mat
([int_0 int_1 int_2 org_opencv_core_scalar_3 ]
  (new org.opencv.core.Mat int_0 int_1 int_2 org_opencv_core_scalar_3 ))
([org_opencv_core_size_0 int_1 org_opencv_core_scalar_2 ]
  (new org.opencv.core.Mat org_opencv_core_size_0 int_1 org_opencv_core_scalar_2 ))
([org_opencv_core_mat_0 org_opencv_core_range_1 ]
  (new org.opencv.core.Mat org_opencv_core_mat_0 org_opencv_core_range_1 ))
([long_0 ]
  (new org.opencv.core.Mat long_0 ))
([]
  (new org.opencv.core.Mat )))
;;;;;;;;;;;;;;;;;;;;;;;;


(defn- buffer-swizzle-0123-1230
  "given a ARGB pixel array, swizzle it to be RGBA.  Or, ABGR to BGRA"
  ^bytes [^bytes data] ;; Wow!  That ^bytes changes this from 10s for a 256x256 tex to instantaneous.
  (dotimes [i (/ (alength data) 4)]
    (let [i0 (* i 4)
          i1 (inc i0)
          i2 (inc i1)
          i3 (inc i2)
          tmp (aget data i0)]
      (aset data i0 (aget data i1))
      (aset data i1 (aget data i2))
      (aset data i2 (aget data i3))
      (aset data i3 tmp)))
  data)

  
(defn- cubemap-filename
  [filename i]
  (clojure.string/replace filename "*" (str i)))

(defn put-texture-data
  "put the data from the image into the buffer and return the buffer"
  ^ByteBuffer
  [^ByteBuffer buffer ^BufferedImage image ^Boolean swizzle-0123-1230]
  (let [data ^bytes (-> ^WritableRaster (.getRaster image)
                         ^DataBufferByte (.getDataBuffer)
                         (.getData))
        data (if swizzle-0123-1230
               (buffer-swizzle-0123-1230 data)
               data)
        buffer (.put buffer data 0 (alength data))] ; (.order (ByteOrder/nativeOrder)) ?
    buffer))

(defn tex-image-bytes
  "return the number of bytes per pixel in this image"
  [^BufferedImage image]
  (let [image-type  (.getType image)
        image-bytes (if (or (= image-type BufferedImage/TYPE_3BYTE_BGR)
                            (= image-type BufferedImage/TYPE_INT_RGB))
                      3
                      (if (or (= image-type BufferedImage/TYPE_4BYTE_ABGR)
                              (= image-type BufferedImage/TYPE_INT_ARGB))
                        4
                        0)) ;; unhandled image type--what to do?
        ;; _           (println "image-type"
        ;;                          (cond
        ;;                           (= image-type BufferedImage/TYPE_3BYTE_BGR)  "TYPE_3BYTE_BGR"
        ;;                           (= image-type BufferedImage/TYPE_INT_RGB)    "TYPE_INT_RGB"
        ;;                           (= image-type BufferedImage/TYPE_4BYTE_ABGR) "TYPE_4BYTE_ABGR"
        ;;                           (= image-type BufferedImage/TYPE_INT_ARGB)   "TYPE_INT_ARGB"
        ;;                           :else image-type))
        _           (assert (pos? image-bytes))] ;; die on unhandled image
    image-bytes))

(defn tex-internal-format
  "return the internal-format for the glTexImage2D call for this image"
  ^Integer
  [^BufferedImage image]
  (let [image-type      (.getType image)
        internal-format (cond
                         (= image-type BufferedImage/TYPE_3BYTE_BGR)  GL11/GL_RGB8
                         (= image-type BufferedImage/TYPE_INT_RGB)    GL11/GL_RGB8
                         (= image-type BufferedImage/TYPE_4BYTE_ABGR) GL11/GL_RGBA8
                         (= image-type BufferedImage/TYPE_INT_ARGB)   GL11/GL_RGBA8)]
    internal-format))

(defn tex-format
  "return the format for the glTexImage2D call for this image"
  ^Integer
  [^BufferedImage image]
  (let [image-type (.getType image)
        format     (cond
                    (= image-type BufferedImage/TYPE_3BYTE_BGR)  GL12/GL_BGR
                    (= image-type BufferedImage/TYPE_INT_RGB)    GL11/GL_RGB
                    (= image-type BufferedImage/TYPE_4BYTE_ABGR) GL12/GL_BGRA
                    (= image-type BufferedImage/TYPE_INT_ARGB)   GL11/GL_RGBA)]
    format))

(defn init-cam [locals cam-id] (let [_              (println "init cam" cam-id )
                                    running-cam     (:running-cam @locals)
                                    running-cam_i   (get running-cam cam-id)
                                    capture-cam     (:capture-cam @locals)
                                    capture-cam_i   (get capture-cam cam-id)]  
                                    (if (= false running-cam_i)(do (swap! locals assoc :running-cam (assoc running-cam cam-id true)) 
                                                                (swap!  locals assoc :capture-cam (assoc capture-cam cam-id (future (oc-capture-from-cam cam-id)) )))
                                                                (do (println "Unable to init cam: " cam-id) ))))
                         
                                                                
                                                                        
                                                                                                    
(defn release-cam-textures [cam-id](let [tmpcams (:cams @the-window-state)
                                        running-cam     (:running-cam @the-window-state)
                                        _         (println "running-cam at release function before release" running-cam)
                                        running-cam_i   (get running-cam cam-id)]
                                        (swap! the-window-state assoc :running-cam (assoc running-cam cam-id false))
                                        (swap! the-window-state assoc :cams (assoc tmpcams cam-id nil))
                                        (println "running-cam at release function after release" (:running-cam @the-window-state))))

    

    
;; ======================================================================
;; code modified from
;; https://github.com/ztellman/penumbra/blob/master/src/penumbra/opengl/core.clj
(defn- get-fields [#^Class static-class]
  (. static-class getFields))
(defn- gl-enum-name
  "Takes the numeric value of a gl constant (i.e. GL_LINEAR), and gives the name"
  [enum-value]
  (if (= 0 enum-value)
    "NONE"
    (.getName #^Field (some
                       #(if (= enum-value (.get #^Field % nil)) % nil)
                       (mapcat get-fields [GL11 GL12 GL13 GL15 GL20])))))
(defn except-gl-errors
  [msg]
  (let [error (GL11/glGetError)
        error-string (str "OpenGL Error(" error "):"
                          (gl-enum-name error) ": " msg)]
    (if (and (not (zero? error)) @throw-on-gl-error)
      (throw (Exception. error-string)))))

;; ======================================================================
(defn- fill-tex-filenames
  "return a vector of 4 items, always.  Use nil if no filename"
  [tex-filenames]
  (apply vector
         (for [i (range no-textures)]
           (if (< i (count tex-filenames))
             (nth tex-filenames i)))))

 (defn- fill-filenames
  [filenames no-filenames]
  (apply vector
         (for [i (range no-filenames)]
           (if (< i (count filenames))
             (nth filenames i)))))            
             
(defn- sort-cams
  [cams_in]
  (let [cams        (remove nil? cams_in)
        fullVec     (vec (replicate no-cams nil))
        newVec      (if (not-empty cams) (apply assoc fullVec (interleave cams cams))(vec (replicate no-cams nil)))
        _           (println "cam vector" newVec)] 
        (into [] newVec)))

 
(defn- sort-videos
  [locals videos_in]
  (let [videos      (remove nil? videos_in)
        fullVec     (vec (replicate no-videos nil))
        _           (swap! locals assoc :video-no-id fullVec)
        ] 
        (doseq [video-filename-idx (range no-videos)]
            (swap! locals assoc :video-no-id (assoc  (:video-no-id @locals)  video-filename-idx  (if (= nil (get videos_in video-filename-idx)) nil  video-filename-idx))))))
   
        
      
(defn- uniform-sampler-type-str
  [tex-types n]
  (format "uniform sampler%s iChannel%s;\n"
          (if (= :cubemap (nth tex-types 0)) "Cube" "2D")
          n))

(defn- slurp-fs
  "do whatever it takes to modify shadertoy fragment shader source to
  be useable"
  [locals filename]
  (let [{:keys [tex-types]} @locals
        ;;file-str (slurp filename)
        file-str (str "#version 120\n"
                      "uniform vec3      iResolution;\n"
                      "uniform float     iGlobalTime;\n"
                      "uniform float     iChannelTime[4];\n"
                      "uniform vec3      iChannelResolution[4];\n"
                      "uniform vec4      iMouse;\n"
                      (uniform-sampler-type-str tex-types 0)
                      (uniform-sampler-type-str tex-types 1)
                      (uniform-sampler-type-str tex-types 2)
                      (uniform-sampler-type-str tex-types 3)
                      "uniform sampler2D iFftWave; \n";
                      "uniform sampler2D iCam0; \n"
                      "uniform sampler2D iCam1; \n"
                      "uniform sampler2D iCam2; \n"
                      "uniform sampler2D iCam3; \n"
                      "uniform sampler2D iCam4; \n"
                      "uniform sampler2D iVideo0; \n"
                      "uniform sampler2D iVideo1; \n"
                      "uniform sampler2D iVideo2; \n"
                      "uniform sampler2D iVideo3; \n"
                      "uniform sampler2D iVideo4; \n"
                      "uniform vec4      iDate;\n"
                      "\n"
                      (slurp filename))]
    file-str))

(defn- cubemap-filename?
  "if a filename contains a '*' char, it is a cubemap"
  [filename]
  (if (string? filename)
    (not (nil? (re-find #"\*" filename)))
    false))

(defn- get-texture-type
  [tex-filename]
  (cond
   (cubemap-filename? tex-filename) :cubemap
   (= :previous-frame tex-filename) :previous-frame
   :default :twod))

(defn- init-window
  "Initialise a shader-powered window with the specified
   display-mode. If true-fullscreen? is true, fullscreen mode is
   attempted if the display-mode is compatible. See display-modes for a
   list of available modes and fullscreen-display-modes for a list of
   fullscreen compatible modes.."
  [locals display-mode title shader-filename shader-str-atom tex-filenames cams videos true-fullscreen? user-fn display-sync-hz]
  (let [width               (.getWidth ^DisplayMode display-mode)
        height              (.getHeight ^DisplayMode display-mode)
        pixel-format        (PixelFormat.)
        context-attributes  (-> (ContextAttribs. 2 1)) ;; GL2.1
        current-time-millis (System/currentTimeMillis)
        tex-filenames       (fill-filenames tex-filenames no-textures)
        videos              (fill-filenames videos no-videos)
        cams                (sort-cams cams)
        tttt                (sort-videos locals videos)
        ;_                   (println "sorted cams" cams)
        tex-types           (map get-texture-type tex-filenames)]
    (swap! locals
           assoc
           :active          :yes
           :width           width
           :height          height
           :title           title
           :display-sync-hz display-sync-hz
           :start-time      current-time-millis
           :last-time       current-time-millis
           :shader-filename shader-filename
           :shader-str-atom shader-str-atom
           :tex-filenames   tex-filenames 
           :cams            cams
           :videos          videos
           :tex-types       tex-types
           :user-fn         user-fn)
    ;; slurp-fs requires :tex-types, so we need a 2 pass setup
    (let [shader-str (if (nil? shader-filename)
                       @shader-str-atom
                       (slurp-fs locals (:shader-filename @locals)))]
      (swap! locals assoc :shader-str shader-str)
      (Display/setDisplayMode display-mode)
      (when true-fullscreen?
        (Display/setFullscreen true))
      (Display/setTitle title)
      (Display/setVSyncEnabled true)
      (Display/setLocation 0 0)
      (Display/create pixel-format context-attributes))
      ))
      
      

(defn- init-buffers
  [locals]
  (let [vertices            (float-array
                             [-1.0 -1.0 0.0 1.0
                               1.0 -1.0 0.0 1.0
                              -1.0  1.0 0.0 1.0
                              -1.0  1.0 0.0 1.0
                               1.0 -1.0 0.0 1.0
                               1.0  1.0 0.0 1.0])
        vertices-buffer     (-> (BufferUtils/createFloatBuffer (count vertices))
                                (.put vertices)
                                (.flip))
        vertices-count      (count vertices)
        vbo-id              (GL15/glGenBuffers)
        _                   (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo-id)
        _                   (GL15/glBufferData GL15/GL_ARRAY_BUFFER
                                           ^FloatBuffer vertices-buffer
                                           GL15/GL_STATIC_DRAW)
        _ (except-gl-errors "@ end of init-buffers")]
        (swap! locals
           assoc
           :vbo-id vbo-id
           :vertices-count vertices-count)))

(def vs-shader
  (str "#version 120\n"
       "void main(void) {\n"
       "    gl_Position = gl_Vertex;\n"
       "}\n"))

(defn- load-shader
  [^String shader-str ^Integer shader-type]
  (let [shader-id         (GL20/glCreateShader shader-type)
        _ (except-gl-errors "@ load-shader glCreateShader ")
        _                 (GL20/glShaderSource shader-id shader-str)
        _ (except-gl-errors "@ load-shader glShaderSource ")
        _                 (GL20/glCompileShader shader-id)
        _ (except-gl-errors "@ load-shader glCompileShader ")
        gl-compile-status (GL20/glGetShaderi shader-id GL20/GL_COMPILE_STATUS)
        _ (except-gl-errors "@ end of let load-shader")]
    (when (== gl-compile-status GL11/GL_FALSE)
      (println "ERROR: Loading a Shader:")
      (println (GL20/glGetShaderInfoLog shader-id 10000)))
    [gl-compile-status shader-id]))

(defn- init-shaders
  [locals]
  (let [[ok? vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
        _           (assert (== ok? GL11/GL_TRUE)) ;; something is really wrong if our vs is bad
        _           (if (nil? (:shader-filename @locals))
                      (println "Loading shader from string")
                      (println "Loading shader from file:" (:shader-filename @locals)))
        [ok? fs-id] (load-shader (:shader-str @locals) GL20/GL_FRAGMENT_SHADER)]
    (if (== ok? GL11/GL_TRUE)
      (let [pgm-id                (GL20/glCreateProgram)
            _ (except-gl-errors "@ let init-shaders glCreateProgram")
            _                     (GL20/glAttachShader pgm-id vs-id)
            _ (except-gl-errors "@ let init-shaders glAttachShader VS")
            _                     (GL20/glAttachShader pgm-id fs-id)
            _ (except-gl-errors "@ let init-shaders glAttachShader FS")
            _                     (GL20/glLinkProgram pgm-id)
            _ (except-gl-errors "@ let init-shaders glLinkProgram")
            gl-link-status        (GL20/glGetProgrami pgm-id GL20/GL_LINK_STATUS)
            _ (except-gl-errors "@ let init-shaders glGetProgram link status")
            _                     (when (== gl-link-status GL11/GL_FALSE)
                                    (println "ERROR: Linking Shaders:")
                                    (println (GL20/glGetProgramInfoLog pgm-id 10000)))
            _ (except-gl-errors "@ let before GetUniformLocation")
            i-resolution-loc      (GL20/glGetUniformLocation pgm-id "iResolution")
            i-global-time-loc     (GL20/glGetUniformLocation pgm-id "iGlobalTime")
            i-channel-time-loc    (GL20/glGetUniformLocation pgm-id "iChannelTime")
            i-mouse-loc           (GL20/glGetUniformLocation pgm-id "iMouse")
            i-channel0-loc        (GL20/glGetUniformLocation pgm-id "iChannel0")
            i-channel1-loc        (GL20/glGetUniformLocation pgm-id "iChannel1")
            i-channel2-loc        (GL20/glGetUniformLocation pgm-id "iChannel2")
            i-channel3-loc        (GL20/glGetUniformLocation pgm-id "iChannel3")
            
            i-fftwave-loc         (GL20/glGetUniformLocation pgm-id "iFftWave")

            i-cam0-loc        (GL20/glGetUniformLocation pgm-id "iCam0")
            i-cam1-loc        (GL20/glGetUniformLocation pgm-id "iCam1")
            i-cam2-loc        (GL20/glGetUniformLocation pgm-id "iCam2")
            i-cam3-loc        (GL20/glGetUniformLocation pgm-id "iCam3")
            i-cam4-loc        (GL20/glGetUniformLocation pgm-id "iCam4")
            
            i-video0-loc        (GL20/glGetUniformLocation pgm-id "iVideo0")
            i-video1-loc        (GL20/glGetUniformLocation pgm-id "iVideo1")
            i-video2-loc        (GL20/glGetUniformLocation pgm-id "iVideo2")
            i-video3-loc        (GL20/glGetUniformLocation pgm-id "iVideo3")
            i-video4-loc        (GL20/glGetUniformLocation pgm-id "iVideo4")
    
            i-channel-res-loc     (GL20/glGetUniformLocation pgm-id "iChannelResolution")
            i-date-loc            (GL20/glGetUniformLocation pgm-id "iDate")

            _ (except-gl-errors "@ end of let init-shaders")
            ]
        (swap! locals
               assoc
               :shader-good true
               :vs-id vs-id
               :fs-id fs-id
               :pgm-id pgm-id
               :i-resolution-loc i-resolution-loc
               :i-global-time-loc i-global-time-loc
               :i-channel-time-loc i-channel-time-loc
               :i-mouse-loc i-mouse-loc
               :i-channel-loc [i-channel0-loc i-channel1-loc i-channel2-loc i-channel3-loc]
               :i-fftwave-loc [i-fftwave-loc]
               :i-cam-loc [i-cam0-loc i-cam1-loc i-cam2-loc i-cam3-loc i-cam4-loc]
               :i-video-loc [i-video0-loc i-video1-loc i-video2-loc i-video3-loc i-video4-loc]
               :i-channel-res-loc i-channel-res-loc
               :i-date-loc i-date-loc))
      ;; we didn't load the shader, don't be drawing
      (swap! locals assoc :shader-good false))))

(defn- load-texture
  "load, bind texture from filename.  returns a texture info vector
   [tex-id width height z].  returns nil tex-id if filename is nil"
  ([^String filename]
     (let [tex-id (GL11/glGenTextures)
     _ (println "tex-id tex" tex-id)]
       (if (cubemap-filename? filename)
         (do
           (dotimes [i 6]
             (load-texture (cubemap-filename filename i)
                           GL13/GL_TEXTURE_CUBE_MAP tex-id i))
           [tex-id 0.0 0.0 0.0]) ;; cubemaps don't update w/h
         (load-texture filename GL11/GL_TEXTURE_2D tex-id 0))))
  ([^String filename ^Integer target ^Integer tex-id ^Integer i]
     (if (string? filename)
       ;; load from file
       (let [_                (println "Loading texture:" filename)
             image            (ImageIO/read (FileInputStream. filename))
             image-bytes      (tex-image-bytes image)
             internal-format  (tex-internal-format image)
             format           (tex-format image)
             nbytes           (* image-bytes (.getWidth image) (.getHeight image))
             buffer           ^ByteBuffer (-> (BufferUtils/createByteBuffer nbytes)
                                              (put-texture-data image (= image-bytes 4))
                                              (.flip))
             tex-image-target ^Integer (if (= target GL13/GL_TEXTURE_CUBE_MAP)
                                         (+ i GL13/GL_TEXTURE_CUBE_MAP_POSITIVE_X)
                                         target)]
             ;_ (println "target" target)
             ;_ (println "tex-id input" tex-id)
         (GL11/glBindTexture target tex-id)
         (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
         (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
         (if (== target GL11/GL_TEXTURE_2D)
           (do
             (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
             (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT))
           (do ;; CUBE_MAP
             (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL12/GL_CLAMP_TO_EDGE)
             (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL12/GL_CLAMP_TO_EDGE)))
         (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
                            ^Integer (.getWidth image)  ^Integer (.getHeight image) 0
                            ^Integer format
                            GL11/GL_UNSIGNED_BYTE
                            ^ByteBuffer buffer)
         (except-gl-errors "@ end of load-texture if-stmt")
         [tex-id (.getWidth image) (.getHeight image) 1.0])
       (if (= filename :previous-frame)
         (do ;; :previous-frame initial setup
           (println "setting up :previous-frame texture")
           (GL11/glBindTexture target tex-id)
           (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
           (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
           (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL12/GL_CLAMP_TO_EDGE)
           (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL12/GL_CLAMP_TO_EDGE)
           (except-gl-errors "@ end of load-texture else-stmt")
           ;; use negative as flag to indicate using window width, height
           [tex-id -1.0 -1.0 1.0])
         ;; else must be nil texture
         [nil 0.0 0.0 0.0]))))

(defn- init-textures
  [locals]
  (let [tex-infos (map load-texture (:tex-filenames @locals))
        ;_ (println "raw" tex-infos)
        tex-ids   (map first tex-infos)

        tex-whd   (map rest tex-infos)
        tex-whd   (flatten
                   (map #(if (< (first %) 0.0)
                           [(:width @locals) (:height @locals) 1.0]
                           %)
                        tex-whd))
        _         (-> ^FloatBuffer (:channel-res-buffer @locals)
                      (.put ^floats (float-array tex-whd))
                      (.flip))
        ]
    (swap! locals assoc
           :tex-ids tex-ids)))

  

 (defn- init-cam-tex [locals cam-id](let [
                                    target              (GL11/GL_TEXTURE_2D)
                                    tex-id             (GL11/glGenTextures)
                                    height              1
                                    width               1
                                    mat  (org.opencv.core.Mat/zeros width height org.opencv.core.CvType/CV_8UC3)
                                    image-bytes         (.channels mat)
                                    nbytes              (* height width image-bytes)
                                    internal-format     GL11/GL_RGB8
                                    format              GL12/GL_BGR
                                    buffer               (oc-mat-to-bytebuffer mat)
                                    image_i (assoc (:image-cam @locals) cam-id buffer)
                                    target_i (assoc (:target-cam @locals) cam-id target)
                                    image-bytes_i (assoc (:image-bytes-cam @locals) cam-id image-bytes)
                                    nbytes_i (assoc (:nbytes-cam @locals) cam-id nbytes)
                                    internal-format_i (assoc (:internal-format-cam @locals) cam-id internal-format)
                                    format_i (assoc (:format-cam @locals) cam-id format)
                                    width_i (assoc (:width-cam @locals) cam-id width)
                                    height_i (assoc (:height-cam @locals) cam-id height)
                                    text-id-cam_i (assoc (:text-id-cam @locals) cam-id tex-id)
                                        ]                                             
                                    (swap! locals
                                        assoc
                                        :image-cam           image_i
                                        :target-cam          target_i
                                        :image-bytes-cam     image-bytes_i
                                        :nbytes-cam          nbytes_i
                                        :internal-format-cam internal-format_i
                                        :format-cam          format_i
                                        :width-cam           width_i
                                        :height-cam          height_i
                                        :text-id-cam         text-id-cam_i)
                                    (GL11/glBindTexture target tex-id)
                                    (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
                                    (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
                                    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
                                    (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)
                                    )      
                                    
                                    )
                            
 (defn- process-cam-image [locals cam-id] (let [
             image                (get (:image-cam @locals) cam-id)
             target               (get (:target-cam @locals) cam-id)
             internal-format (get (:internal-format-cam @locals) cam-id)
             format (get (:format-cam @locals) cam-id)
             height (get (:height-cam @locals) cam-id)
             width (get (:width-cam @locals) cam-id)
             tex-id             (get (:text-id-cam @locals) cam-id)
             tex-image-target ^Integer (+ 0 target)
             ]
      (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id))
      (GL11/glBindTexture target tex-id)
      (GL11/glTexImage2D tex-image-target, 0, internal-format, width, height, 0, format, GL11/GL_UNSIGNED_BYTE, image)
))         


(defn- buffer-cam-texture [locals cam-id capture-cam](let [
             target           (GL11/GL_TEXTURE_2D)
             tex-id             (get (:text-id-cam @locals) cam-id)
             image              (oc-new-mat)
             imageP             (oc-query-frame @capture-cam image)
             height         (.height image)
             width          (.width image)          
             image-bytes        (.channels image)
             internal-format     GL11/GL_RGB8
             format              GL12/GL_BGR
             nbytes               (* width height image-bytes)
             buffer             (oc-mat-to-bytebuffer image)
             
             image_i (assoc (:image-cam @locals) cam-id buffer)
             target_i (assoc (:target-cam @locals) cam-id target)
             image-bytes_i (assoc (:image-bytes-cam @locals) cam-id image-bytes)
             nbytes_i (assoc (:nbytes-cam @locals) cam-id nbytes)
             internal-format_i (assoc (:internal-format-cam @locals) cam-id internal-format)
             format_i (assoc (:format-cam @locals) cam-id format)
             width_i (assoc (:width-cam @locals) cam-id width)
             height_i (assoc (:height-cam @locals) cam-id height)
             text-id-cam_i (assoc (:text-id-cam @locals) cam-id tex-id)
             ]                                             
             (swap! locals
                    assoc
                    :image-cam           image_i
                    :target-cam          target_i
                    :image-bytes-cam     image-bytes_i
                    :nbytes-cam          nbytes_i
                    :internal-format-cam internal-format_i
                    :format-cam          format_i
                    :width-cam           width_i
                    :height-cam          height_i
                    :text-id-cam         text-id-cam_i)
             
            ;(put-cam-buffer locals buffer target image-bytes nbytes internal-format format height width  cam-id tex-id)

             )) 
 
 

(defn- start-cam-loop [locals cam-id]
    (let [_ (println "start cam loop " cam-id)
            running-cam     (:running-cam @locals)
            running-cam_i   (get running-cam cam-id)
            capture-cam     (:capture-cam @locals)
            capture-cam_i   (get capture-cam cam-id)
            ]
        (if (= true running-cam_i) 
            (do (while  (get (:running-cam @locals) cam-id)
                (buffer-cam-texture locals cam-id capture-cam_i))(oc-release @capture-cam_i)(println "cam loop stopped" cam-id)))))   

                
                                     
;;;;;;;;;;;;;;;;;
;;Video functions
;;;;;;;;;;;;;;;;;
(defn set-video-frame 
    [video-id frame] 
    (let [  running-video       (:running-video @the-window-state)
            running-video_i     (get running-video video-id)
            capture-video       (:capture-video @the-window-state)
            capture-video_i     (get capture-video video-id)
            frame-count         @(nth (:frames-video @the-window-state) video-id)
            frame               (if (< frame frame-count)  frame frame-count)
            frame               (if (> frame 1) frame 1)
            frame-ctr-video     (:frame-ctr-video @the-window-state)]
            (reset! (nth (:frame-ctr-video @the-window-state) video-id) frame)
            (reset! (nth (:frame-change-video @the-window-state) video-id) true)))
                                     
(defn set-video-frame-limits 
    [video-id min max] 
    (let [  running-video       (:running-video @the-window-state)
            running-video_i     (get running-video video-id)
            capture-video       (:capture-video @the-window-state)
            capture-video_i     (get capture-video video-id)
            frame-count         @(nth (:frames-video @the-window-state) video-id)
            min_val             (if  (and (< min max) ( <= min frame-count) (> min 0)) min 1) 
            max_val             (if  (and (< min max) ( <= max frame-count) (> max 0)) max frame-count)
            cur_pos             (oc-get-capture-property :pos-frames capture-video_i )]
            (reset! (nth (:frame-start-video @the-window-state) video-id) min_val)
            (reset! (nth (:frame-stop-video @the-window-state) video-id) max_val)
            (if (< cur_pos min_val) (set-video-frame video-id min_val) (if (> cur_pos max_val) (set-video-frame video-id max_val) 0 ))))
                          

(defn set-video-fps 
    [video-id new-fps] 
    (let [  running-video     (:running-video @the-window-state)
            running-video_i     (get running-video video-id)
            capture-video       (:capture-video @the-window-state)
            capture-video_i     (get capture-video video-id) 
            fps (oc-get-capture-property :fps capture-video_i )
            fpstbs (if (< 0 new-fps) new-fps 1)
            _ (reset! ( nth (:fps-video @the-window-state) video-id) fpstbs)]
            (oc-set-capture-property :fps capture-video_i  fpstbs)
            (println "new fps " fpstbs )))
                                     

 
(defn init-vbuff 
   [locals video-id] 
   (let [  capture-video        (:capture-video @locals)
           capture-video_i      (get capture-video video-id)
           image                (oc-new-mat)
           imageP               (oc-query-frame capture-video_i image)
           height               (.height image)
           width                (.width image)
           image-bytes          (.channels image)
           internal-format      GL11/GL_RGB8
           format               GL12/GL_BGR
           frame-count          (oc-get-capture-property :frame-count  capture-video_i )
           fps                  (oc-get-capture-property :fps capture-video_i)
           nbytes               (* image-bytes width height)
           bff                  (BufferUtils/createByteBuffer nbytes)
           buffer               (oc-mat-to-bytebuffer image)
           width_i              (assoc (:width-video @locals) video-id width)
           height_i             (assoc (:height-video @locals) video-id height)
           frames-video_i       (assoc (:frames-video @locals) video-id frame-count)
           fps-video_i          (assoc (:fps-video @locals) video-id fps) 
           _ (reset! (nth (:buffer-video-frame @locals) video-id) image)
           _ (reset! (nth (:frame-start-video @locals) video-id)   1 )
           _ (reset! (nth (:frame-stop-video @locals) video-id) frame-count)
           _ (reset! (nth (:internal-format-video @locals) video-id) internal-format)
           _ (reset! (nth (:format-video @locals) video-id) format)
           _ (reset! (nth (:fps-video @locals) video-id) fps)
           _ (reset! (nth (:width-video @locals) video-id) width)
           _ (reset! (nth (:height-video @locals) video-id) height)
           _ (reset! (nth (:frames-video @locals) video-id) frame-count)
           _ (set-video-frame-limits video-id 1 frame-count)] ))  
                          
(defn release-video-textures 
    [video-id]
    (let[tmpvideos          (:videos @the-window-state)
        tmp-video-ids       (:video-no-id @the-window-state)
        running-video       (:running-video @the-window-state)
        running-video_i   (get running-video video-id)]
        (swap! the-window-state assoc :running-video (assoc running-video video-id false))
        (swap! the-window-state assoc :videos (assoc tmpvideos video-id nil))
        (swap! the-window-state assoc :video-no-id (assoc tmp-video-ids video-id nil))
        (println ":running-video at release function after release" (:running-video @the-window-state))
        (println ":video-no-id at release function after release" (:video-no-id @the-window-state))
        (println ":videos at release function after release" (:videos @the-window-state))))    

     
 
 
 
 
(defn- init-video-tex 
    [locals video-id]
    (let [  target              (GL11/GL_TEXTURE_2D)
            tex-id              (GL11/glGenTextures)
            height              1
            width               1
            mat                 (org.opencv.core.Mat/zeros width height org.opencv.core.CvType/CV_8UC3)
            image-bytes         (.channels mat)
            nbytes              (* height width image-bytes)
            internal-format     GL11/GL_RGB8
            format              GL12/GL_BGR
            buffer              (oc-mat-to-bytebuffer mat)
            _ (reset! (nth (:target-video @locals) video-id) target)
            _ (reset! (nth (:text-id-video @locals) video-id) tex-id)
            _ (reset! (nth (:internal-format-video @locals) video-id) internal-format)
            _ (reset! (nth (:format-video @locals) video-id) format)
            _ (reset! (nth (:fps-video @locals) video-id) 1)
            _ (reset! (nth (:width-video @locals) video-id) width)
            _ (reset! (nth (:height-video @locals) video-id) height)
            _ (reset! (nth (:frames-video @locals) video-id) 1)]
            (GL11/glBindTexture target tex-id)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)))
 
        
 
 
(defn- process-video-image 
    [locals video-id] 
    (let[   target              @(nth (:target-video @locals) video-id)
            internal-format     @(nth (:internal-format-video @locals) video-id)
            format              @(nth (:format-video @locals) video-id)
            image               @(nth (:buffer-video-frame @locals) video-id)
            height              (.height image)
            width               (.width image)          
            image-bytes         (.channels image)
            tex-id              @(nth (:text-id-video @locals) video-id)
            tex-image-target    ^Integer (+ 0 target)
            nbytes              (* width height image-bytes)
            buffer              (oc-mat-to-bytebuffer image)]
            (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id))
            (GL11/glBindTexture target tex-id)
            (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
                ^Integer width  ^Integer height 0
                ^Integer format
                GL11/GL_UNSIGNED_BYTE
                ^ByteBuffer buffer))
            (except-gl-errors "@ end of load-texture if-stmt")))
 
 
(defn- buffer-video-texture 
    [locals video-id capture-video]
    (let [  image              (oc-new-mat)
            imageP             (oc-query-frame capture-video image)
            _                   (reset! (nth (:buffer-video-frame @locals) video-id) image)]))
    
 
(defn- start-video-loop 
    [locals video-id]
    (let [_ (println "start video loop " video-id)
            running-video     (:running-video @locals)
            running-video_i   (get running-video video-id)
            capture-video     (:capture-video @locals)
            capture-video_i   (get capture-video video-id)
            frame-count       (oc-get-capture-property :frame-count capture-video_i )
            cur-frame         (oc-get-capture-property :pos-frames capture-video_i )
            cur-fps           (oc-get-capture-property :fps capture-video_i )
            locKey            (keyword (str "frame-ctr-"video-id))
            startTime         (atom (System/nanoTime))]
            (if (= true running-video_i) 
                (do (while  (get (:running-video @locals) video-id)
                    (reset! startTime (System/nanoTime))
                    (if (< (oc-get-capture-property :pos-frames capture-video_i ) @(nth (:frame-stop-video @locals) video-id))
                        (buffer-video-texture locals video-id capture-video_i)
                        (oc-set-capture-property :pos-frames capture-video_i  @(nth (:frame-start-video @locals) video-id))) 
                
                    (if (= true @(nth (:frame-change-video @locals) video-id)) 
                        (do(oc-set-capture-property :pos-frames  capture-video_i  @(nth (:frame-ctr-video @locals) video-id) )
                            (reset! (nth (:frame-change-video @locals) video-id) false))
                        (Thread/sleep (sleepTime @startTime (System/nanoTime) @(nth (:fps-video @locals) video-id)))))
                    (oc-release capture-video_i)
                    (println "video loop stopped" video-id)))))   
    
(defn- check-video-idx 
   [locals video-id]
   (let [  _                   (println "init video" video-id )
           running-video       (:running-video @locals)
           running-video_i     (get running-video video-id)
           capture-video       (:capture-video @locals)
           capture-video_i     (get capture-video video-id)
           video-filename      (:videos @locals)
           video-filename_i    (get video-filename video-id)
           video-no-id         (:video-no-id @locals)] 
           (if (and (not-nil? video-filename_i) (= false running-video_i)(.exists (io/file video-filename_i)))
               (do (println "video tb init"video-filename_i)
               (swap!  locals assoc :capture-video (assoc capture-video video-id (oc-capture-from-video video-filename_i) ))
               (swap! locals assoc :running-video (assoc running-video video-id true))
               (init-vbuff locals video-id)
               (if (.isOpened (get (:capture-video @locals) video-id))
                   (do (future (start-video-loop locals video-id)))
                   (do (swap! locals assoc :running-video (assoc running-video video-id false))
                       (oc-release capture-video_i)
                       (swap! locals assoc :videos (set-nil video-filename video-id))
                       (println " bad video " video-id))))
               (do (println "Unable to init video: " video-id) ))))   
                
(defn post-start-video 
    [video-filename video-id] 
    (let [  tmpvideo            (:videos @the-window-state)
            tmpvideo_ids        (:video-no-id @the-window-state)
            capture-video       (:capture-video @the-window-state)
            capture-video_i     (get capture-video video-id)] 
            (release-video-textures video-id)
            (Thread/sleep 100)
            (swap! the-window-state assoc :videos (assoc tmpvideo video-id video-filename))
            (swap! the-window-state assoc :video-no-id (assoc tmpvideo_ids video-id video-id))
            (check-video-idx the-window-state video-id)))
;;;;;;;;;;;;;;;;;;;;;;;;;;                                                        
                                                                                                    
(defn- remove-if-bad [locals cam-id] (let [cams_tmp (:cams @locals)            
                                            running-cam     (:running-cam @locals)
                                            running-cam_i   (get running-cam cam-id)
                                            capture-cam     (:capture-cam @locals)
                                            capture-cam_i   (get capture-cam cam-id)](do 
(swap! locals assoc :running-cam (assoc running-cam cam-id false)) 
(oc-release @capture-cam_i)
(swap! locals assoc :cams (set-nil cams_tmp cam-id))))) 


(defn- check-cam-idx [locals cam-id](let  [running-cam     (:running-cam @locals)
                                        running-cam_i   (get running-cam cam-id)
                                        capture-cam     (:capture-cam @locals)
                                        capture-cam_i   (get capture-cam cam-id)] (cond
        (= cam-id nil) (println "no cam")
        :else (do (init-cam locals cam-id) (if (.isOpened @(get (:capture-cam @locals) cam-id))(do (future (start-cam-loop locals cam-id)))(do (remove-if-bad locals cam-id)(println " bad cam " cam-id)))
))))


(defn- init-cams
[locals]
(let [cam_idxs        (:cams @locals)]
    (doseq [cam-id (range no-cams)]
        ;(println "cam-id init" cam-id)
        (init-cam-tex locals cam-id ) 
    )
    (doseq [cam-id cam_idxs]
        (println "cam_id" cam-id)
        (check-cam-idx locals cam-id))))    

    
(defn- init-videos 
    [locals]
    (let [  video_idxs        (:videos @locals)]
            (doseq [video-id (range no-videos)]
                (init-video-tex locals video-id ))
            (doseq [video-id (range no-videos)]
                (println "video_id" video-id)
                (check-video-idx locals video-id))))    
    
(defn post-start-cam [cam-id] (let [tmpcams (:cams @the-window-state)] 
    (release-cam-textures cam-id)
    (Thread/sleep 10)
    (check-cam-idx the-window-state cam-id)
    (swap! the-window-state assoc :cams (assoc tmpcams cam-id cam-id))))


      
(defn- init-gl
  [locals]
  (let [{:keys [width height user-fn]} @locals]
    ;;(println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GL11/glClearColor 0.0 0.0 0.0 0.0)
    (GL11/glViewport 0 0 width height)
    (init-buffers locals)
    (init-textures locals)

    (init-cams locals)
    (Thread/sleep 100)
    (init-videos locals)
    (init-shaders locals)
    (swap! locals assoc :tex-id-fftwave (GL11/glGenTextures))
    (when (and (not (nil? user-fn)) (:shader-good @locals))
      (user-fn :init (:pgm-id @locals) (:tex-id-fftwave @locals)))))

(defn- try-reload-shader
  [locals]
  (let [{:keys [vs-id fs-id pgm-id shader-filename user-fn]} @locals
        vs-id (if (= vs-id 0)
                (let [[ok? vs-id] (load-shader vs-shader GL20/GL_VERTEX_SHADER)
                      _ (assert (== ok? GL11/GL_TRUE))]
                  vs-id)
                vs-id)
        fs-shader       (if (nil? shader-filename)
                          @reload-shader-str
                          (slurp-fs locals shader-filename))
        [ok? new-fs-id] (load-shader fs-shader GL20/GL_FRAGMENT_SHADER)
        _               (reset! reload-shader false)]
    (if (== ok? GL11/GL_FALSE)
      ;; we didn't reload a good shader. Go back to the old one if possible
      (when (:shader-good @locals)
        (GL20/glUseProgram pgm-id)
        (except-gl-errors "@ try-reload-shader useProgram1"))
      ;; the load shader went well, keep going...
      (let [new-pgm-id     (GL20/glCreateProgram)
            _ (except-gl-errors "@ try-reload-shader glCreateProgram")
            _              (GL20/glAttachShader new-pgm-id vs-id)
            _ (except-gl-errors "@ try-reload-shader glAttachShader VS")
            _              (GL20/glAttachShader new-pgm-id new-fs-id)
            _ (except-gl-errors "@ try-reload-shader glAttachShader FS")
            _              (GL20/glLinkProgram new-pgm-id)
            _ (except-gl-errors "@ try-reload-shader glLinkProgram")
            gl-link-status (GL20/glGetProgrami new-pgm-id GL20/GL_LINK_STATUS)
            _ (except-gl-errors "@ end of let try-reload-shader")]
        (if (== gl-link-status GL11/GL_FALSE)
          (do
            (println "ERROR: Linking Shaders: (reloading previous program)")
            (println (GL20/glGetProgramInfoLog new-pgm-id 10000))
            (GL20/glUseProgram pgm-id)
            (except-gl-errors "@ try-reload-shader useProgram2"))
          (let [_ (println "Reloading shader:" shader-filename)
                i-resolution-loc   (GL20/glGetUniformLocation new-pgm-id "iResolution")
                i-global-time-loc  (GL20/glGetUniformLocation new-pgm-id "iGlobalTime")
                i-channel-time-loc (GL20/glGetUniformLocation new-pgm-id "iChannelTime")
                i-mouse-loc        (GL20/glGetUniformLocation new-pgm-id "iMouse")
                i-channel0-loc     (GL20/glGetUniformLocation new-pgm-id "iChannel0")
                i-channel1-loc     (GL20/glGetUniformLocation new-pgm-id "iChannel1")
                i-channel2-loc     (GL20/glGetUniformLocation new-pgm-id "iChannel2")
                i-channel3-loc     (GL20/glGetUniformLocation new-pgm-id "iChannel3")
                
                i-fftwave-loc         (GL20/glGetUniformLocation new-pgm-id "iFftWave")
                
                i-cam0-loc        (GL20/glGetUniformLocation new-pgm-id "iCam0")
                i-cam1-loc        (GL20/glGetUniformLocation new-pgm-id "iCam1")
                i-cam2-loc        (GL20/glGetUniformLocation new-pgm-id "iCam2")
                i-cam3-loc        (GL20/glGetUniformLocation new-pgm-id "iCam3")
                i-cam4-loc        (GL20/glGetUniformLocation new-pgm-id "iCam4")
                
                i-video0-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo0")
                i-video1-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo1")
                i-video2-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo2")
                i-video3-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo3")
                i-video4-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo4")

                i-channel-res-loc  (GL20/glGetUniformLocation new-pgm-id "iChannelResolution")
                i-date-loc         (GL20/glGetUniformLocation new-pgm-id "iDate")]
            (GL20/glUseProgram new-pgm-id)
            (except-gl-errors "@ try-reload-shader useProgram")
            (when user-fn
              (user-fn :init new-pgm-id (:tex-id-fftwave @locals)))
            ;; cleanup the old program
            (when (not= pgm-id 0)
              (GL20/glDetachShader pgm-id vs-id)
              (GL20/glDetachShader pgm-id fs-id)
              (GL20/glDeleteShader fs-id))
            (except-gl-errors "@ try-reload-shader detach/delete")
            (swap! locals
                   assoc
                   :shader-good true
                   :fs-id new-fs-id
                   :pgm-id new-pgm-id
                   :i-resolution-loc i-resolution-loc
                   :i-global-time-loc i-global-time-loc
                   :i-channel-time-loc i-channel-time-loc
                   :i-mouse-loc i-mouse-loc
                   :i-channel-loc [i-channel0-loc i-channel1-loc i-channel2-loc i-channel3-loc]
                   :i-fftwave-loc [i-fftwave-loc]
                   :i-cam-loc [i-cam0-loc i-cam1-loc i-cam2-loc i-cam3-loc i-cam4-loc]
                   :i-video-loc [i-video0-loc i-video1-loc i-video2-loc i-video3-loc i-video4-loc]
                   :i-channel-res-loc i-channel-res-loc
                   :i-date-loc i-date-loc
                   :shader-str fs-shader)))))))

(defn- get-pixel-value
  [^ByteBuffer rgb-bytes]
  (let [rf (/ (float (int (bit-and 0xFF (.get rgb-bytes 0)))) 255.0)
        gf (/ (float (int (bit-and 0xFF (.get rgb-bytes 1)))) 255.0)
        bf (/ (float (int (bit-and 0xFF (.get rgb-bytes 2)))) 255.0)]
    [rf gf bf]))

(defn- get-cam-textures[locals cam-id](let[running-cam     (:running-cam @locals)
                                            running-cam_i   (get running-cam cam-id)]
                                            (if (and (= true running-cam_i)( not-nil?(get (:image-cam @locals) cam-id)))(do (process-cam-image locals cam-id)) :false)))
(defn- get-video-textures[locals video-id](let[running-video     (:running-video @locals)
                                            running-video_i   (get running-video video-id)
                                            ]
                                            (if (and (= true running-video_i))(do (process-video-image locals video-id)) :false)))
(defn- loop-get-cam-textures [locals cams]
                (doseq [i (remove nil? cams)]
                (get-cam-textures locals i)))
                
(defn- loop-get-video-textures [locals videos](let [{:keys [video-no-id]} @locals]
                (doseq [i (remove nil? video-no-id)]
                (get-video-textures locals i))))
                                            
(defn- draw
  [locals]
  (let [{:keys [width height i-resolution-loc
                start-time last-time i-global-time-loc
                i-date-loc
                pgm-id vbo-id
                vertices-count
                i-mouse-loc
                mouse-pos-x mouse-pos-y
                mouse-ori-x mouse-ori-y
                i-channel-time-loc i-channel-loc i-fftwave-loc i-cam-loc i-video-loc
                i-channel-res-loc
                channel-time-buffer channel-res-buffer
                old-pgm-id old-fs-id
                tex-ids cams text-id-cam videos text-id-video tex-types
                user-fn
                pixel-read-enable
                pixel-read-pos-x pixel-read-pos-y
                pixel-read-data]} @locals
        cur-time    (/ (- last-time start-time) 1000.0)
        _           (.put ^FloatBuffer channel-time-buffer 0 (float cur-time))
        _           (.put ^FloatBuffer channel-time-buffer 1 (float cur-time))
        _           (.put ^FloatBuffer channel-time-buffer 2 (float cur-time))
        _           (.put ^FloatBuffer channel-time-buffer 3 (float cur-time))
        cur-date    (Calendar/getInstance)
        cur-year    (.get cur-date Calendar/YEAR)         ;; four digit year
        cur-month   (.get cur-date Calendar/MONTH)        ;; month 0-11
        cur-day     (.get cur-date Calendar/DAY_OF_MONTH) ;; day 1-31
        cur-seconds (+ (* (.get cur-date Calendar/HOUR_OF_DAY) 60.0 60.0)
                       (* (.get cur-date Calendar/MINUTE) 60.0)
                       (.get cur-date Calendar/SECOND))]

    (except-gl-errors "@ draw before clear")

    (GL11/glClear GL11/GL_COLOR_BUFFER_BIT)

    (when user-fn
      (user-fn :pre-draw pgm-id (:tex-id-fftwave @locals)))

    ;; activate textures
    ;(print "tex-ids" tex-ids)
    (dotimes [i (count tex-ids)]

      (when (nth tex-ids i)
        (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
        ;(println "(nth tex-ids i) i" (nth tex-ids i))
        (cond
         (= :cubemap (nth tex-types i))
         (GL11/glBindTexture GL13/GL_TEXTURE_CUBE_MAP (nth tex-ids i))
         (= :previous-frame (nth tex-types i))
         (GL11/glBindTexture GL11/GL_TEXTURE_2D (nth tex-ids i))
         :default
         (GL11/glBindTexture GL11/GL_TEXTURE_2D (nth tex-ids i)))))

    (except-gl-errors "@ draw after activate textures")
    
    (loop-get-cam-textures locals cams)
    (loop-get-video-textures locals videos)

    ;; setup our uniform
    (GL20/glUniform3f i-resolution-loc width height 1.0)
    (GL20/glUniform1f i-global-time-loc cur-time)
    (GL20/glUniform1  ^Integer i-channel-time-loc ^FloatBuffer channel-time-buffer)
    (GL20/glUniform4f i-mouse-loc
                      mouse-pos-x
                      mouse-pos-y
                      mouse-ori-x
                      mouse-ori-y)
    (GL20/glUniform1i (nth i-channel-loc 0) 1)
    (GL20/glUniform1i (nth i-channel-loc 1) 2)
    (GL20/glUniform1i (nth i-channel-loc 2) 3)
    (GL20/glUniform1i (nth i-channel-loc 3) 4)
    (GL20/glUniform1i (nth i-cam-loc 0) 5)  
    (GL20/glUniform1i (nth i-cam-loc 1) 6)
    (GL20/glUniform1i (nth i-cam-loc 2) 7)
    (GL20/glUniform1i (nth i-cam-loc 3) 8)
    (GL20/glUniform1i (nth i-cam-loc 4) 9)
    (GL20/glUniform1i (nth i-video-loc 0) 10)
    (GL20/glUniform1i (nth i-video-loc 1) 11)
    (GL20/glUniform1i (nth i-video-loc 2) 12)
    (GL20/glUniform1i (nth i-video-loc 3) 13)
    (GL20/glUniform1i (nth i-video-loc 4) 14)
    (GL20/glUniform1i (nth i-fftwave-loc 0) 15)


    (GL20/glUniform3  ^Integer i-channel-res-loc ^FloatBuffer channel-res-buffer)
    (GL20/glUniform4f i-date-loc cur-year cur-month cur-day cur-seconds)
    ;; get vertex array ready
    (GL11/glEnableClientState GL11/GL_VERTEX_ARRAY)
    (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo-id)
    (GL11/glVertexPointer 4 GL11/GL_FLOAT 0 0)

    (except-gl-errors "@ draw prior to DrawArrays")

    ;; Draw the vertices
    (GL11/glDrawArrays GL11/GL_TRIANGLES 0 vertices-count)
    
    (except-gl-errors "@ draw after DrawArrays")
    
    ;; Put everything back to default (deselect)
    (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
    (GL11/glDisableClientState GL11/GL_VERTEX_ARRAY)
    ;; unbind textures
    (doseq [i (remove nil? tex-ids)]
        (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
        (GL11/glBindTexture GL13/GL_TEXTURE_CUBE_MAP 0)
        (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
        ;)
    ;cams
    (doseq [i text-id-cam] 
        (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
        (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
    ;videos
    (doseq [i text-id-video]
        (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 @i))
        (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
        
        
    (except-gl-errors "@ draw prior to post-draw")

    (when user-fn
      (user-fn :post-draw pgm-id (:tex-id-fftwave @locals)))
    
    
    (except-gl-errors "@ draw after post-draw")
    (GL20/glUseProgram 0)
    ;; copy the rendered image
    (dotimes [i (count tex-ids)]
      (when (= :previous-frame (nth tex-types i))
        (GL11/glBindTexture GL11/GL_TEXTURE_2D (nth tex-ids i))
        (GL11/glCopyTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGBA8 0 0 width height 0)
        (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)))
    (except-gl-errors "@ draw after copy")

    ;; read a pixel value
    (when pixel-read-enable
      (GL11/glReadPixels ^Integer pixel-read-pos-x ^Integer pixel-read-pos-y
                        1 1
                        GL11/GL_RGB GL11/GL_UNSIGNED_BYTE
                        ^ByteBuffer pixel-read-data)
      (except-gl-errors "@ draw after pixel read")
      (reset! pixel-value (get-pixel-value ^ByteBuffer pixel-read-data)))))

(defn- update-and-draw
  [locals]
  (let [{:keys [width height last-time pgm-id
                mouse-pos-x mouse-pos-y
                mouse-clicked mouse-ori-x mouse-ori-y]} @locals
                cur-time (System/currentTimeMillis)
                cur-mouse-clicked (Mouse/isButtonDown 0)
                mouse-down-event (and cur-mouse-clicked (not mouse-clicked))
                cur-mouse-pos-x (if cur-mouse-clicked (Mouse/getX) mouse-pos-x)
                cur-mouse-pos-y (if cur-mouse-clicked (Mouse/getY) mouse-pos-y)
                cur-mouse-ori-x (if mouse-down-event
                          (Mouse/getX)
                          (if cur-mouse-clicked
                            mouse-ori-x
                            (- (Math/abs ^float mouse-ori-x))))
                cur-mouse-ori-y (if mouse-down-event
                          (Mouse/getY)
                          (if cur-mouse-clicked
                            mouse-ori-y
                            (- (Math/abs ^float mouse-ori-y))))]
    (swap! locals
           assoc
           :last-time cur-time
           :mouse-clicked cur-mouse-clicked
           :mouse-pos-x cur-mouse-pos-x
           :mouse-pos-y cur-mouse-pos-y
           :mouse-ori-x cur-mouse-ori-x
           :mouse-ori-y cur-mouse-ori-y)
    (if (:shader-good @locals)
      (do
        (if @reload-shader
          (try-reload-shader locals)  ; this must call glUseProgram
          (GL20/glUseProgram pgm-id)) ; else, normal path...
        (draw locals))
      ;; else clear to prevent strobing awfulness
      (do
        (GL11/glClear GL11/GL_COLOR_BUFFER_BIT)
        (except-gl-errors "@ bad-draw glClear ")
        (if @reload-shader
          (try-reload-shader locals))))))

          
(defn- destroy-gl
  [locals]
  (let [{:keys [pgm-id vs-id fs-id vbo-id user-fn cams]} @locals]
     ;;Stop and release cams
    (println " Cams tbd" (:cams @the-window-state))
    (doseq [i (remove nil? (:cams @the-window-state))](println "release cam " i)(release-cam-textures i))
    (swap! locals assoc :cams (vec (replicate no-cams nil)))
    
    (println " Videos tbd" (:videos @the-window-state))
    (doseq [i (remove nil? (:video-no-id @the-window-state))](println "release videos " i)(release-video-textures i))
    (swap! locals assoc :videos (vec (replicate no-videos nil)))
    ;Stop and release video release-cam-textures
    ;; Delete any user state
    (when user-fn
      (user-fn :destroy pgm-id (:tex-id-fftwave @locals)))
    ;; Delete the shaders
    (GL20/glUseProgram 0)
    (GL20/glDetachShader pgm-id vs-id)
    (GL20/glDetachShader pgm-id fs-id)
    (GL20/glDeleteShader vs-id)
    (GL20/glDeleteShader fs-id)
    (GL20/glDeleteProgram pgm-id)
    ;; Delete the vertex VBO
    (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
    (GL15/glDeleteBuffers ^Integer vbo-id)))

(defn- run-thread
  [locals mode shader-filename shader-str-atom tex-filenames cams videos title true-fullscreen? user-fn display-sync-hz]
  (init-window locals mode title shader-filename shader-str-atom tex-filenames cams videos true-fullscreen? user-fn display-sync-hz)
  (init-gl locals)
  (def sharedD (new SharedDrawable (Display/getDrawable)))
  ;(println "sharedD" (. sharedD isCurrent))
  (while (and (= :yes (:active @locals))
              (not (Display/isCloseRequested)))
    (update-and-draw locals)
    (Display/update)
    (Display/sync (:display-sync-hz @locals)))
  (destroy-gl locals)
  (Display/destroy)
    
  (swap! locals assoc :active :no)

  )

(defn- good-tex-count
  [textures]
  (if (<= (count textures) no-textures)
    true
    (do
      (println "ERROR: number of textures must be <= " no-textures)
      false)))

(defn- expand-filename
  "if there is a cubemap filename, expand it 0..5 for the
  cubemaps. otherwise leave it alone."
  [filename]
  (if (cubemap-filename? filename)
    (for [i (range 6)] (cubemap-filename filename i))
    filename))

(defn- files-exist
  "check to see that the filenames actually exist.  One tweak is to
  allow nil or keyword 'filenames'.  Those are important placeholders.
  Another tweak is to expand names for cubemap textures."
  [filenames]
  (let [full-filenames (flatten (map expand-filename filenames))]
    (reduce #(and %1 %2) ; kibit keep
            (for [fn full-filenames]
              (if (or (nil? fn)
                      (and (keyword? fn) (= fn :previous-frame))
                      (.exists (File. ^String fn)))
                true
                (do
                  (println "ERROR:" fn "does not exist.")
                  false))))))

(defn- sane-user-inputs
  [mode shader-filename shader-str textures title true-fullscreen? user-fn]
  (and (good-tex-count textures)
       (files-exist (flatten [shader-filename textures]))
       (not (and (nil? shader-filename) (nil? shader-str)))))

;; watch the shader-str-atom to reload on a change
(defn- watch-shader-str-atom
  [key identity old new]
  (when (not= old new)
    ;; if already reloading, wait for that to finish
    (while @reload-shader
      ;; FIXME this can hang.  We should timeout instead
      (Thread/sleep 100))
    (reset! reload-shader-str new)
    (reset! reload-shader true)))

;; watch the shader directory & reload the current shader if it changes.
(defn- if-match-reload-shader
  [shader-filename files]
  (if @watcher-just-started
    ;; allow first, automatic call to pass unnoticed
    (reset! watcher-just-started false)
    ;; otherwise do the reload check
    (doseq [f files]
      (when (= (.getPath ^File f) shader-filename)
        ;; set a flag that the opengl thread will use
        (reset! reload-shader true)))))

(defn- start-watcher
  "create a watch for glsl shaders in the directory and return the global
  future atom for that watcher"
  [shader-filename]
  (let [dir (.getParent (File. ^String shader-filename))
        _   (println "dir" dir)]
    (reset! watcher-just-started true)
    (watcher/watcher
     [dir]
     (watcher/rate 100)
     (watcher/file-filter watcher/ignore-dotfiles)
     (watcher/file-filter (watcher/extensions :glsl))
     (watcher/on-change (partial if-match-reload-shader shader-filename)))))

(defn- stop-watcher
  "given a watcher-future f, put a stop to it"
  [f]
  (when-not (or (future-done? f) (future-cancelled? f))
    (if (not (future-cancel f))
      (println "ERROR: unable to stop-watcher!"))))
  

  
 

;; ======================================================================
;; allow shader to have user-data, just like tone.
;; I'd like to make this better follow DRY, but this seems okay for now
(defonce shader-user-data (atom {}))
(defonce shader-user-locs (atom {}))
(defn- shader-default-fn
  [dispatch pgm-id tex-id-i]
  (case dispatch ;; FIXME defmulti?
    :init ;; find Uniform Location
    (doseq [key (keys @shader-user-data)]
      (let [loc (GL20/glGetUniformLocation ^Integer pgm-id ^String key)]
        (swap! shader-user-locs assoc key loc)))
    :pre-draw
    (doseq [key (keys @shader-user-data)]
      (let [loc (@shader-user-locs key)
            val (deref (@shader-user-data key))]
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
      )


;; Public API ===================================================

(defn display-modes
  "Returns a seq of display modes sorted by resolution size with highest
   resolution first and lowest last."
  []
  (sort (fn [^DisplayMode a ^DisplayMode b]
          (let [res-a       (* (.getWidth a)
                               (.getHeight a))
                res-b       (* (.getWidth b)
                               (.getHeight b))
                bit-depth-a (.getBitsPerPixel a)
                bit-depth-b (.getBitsPerPixel b) ]
            (if (= res-a res-b)
              (> bit-depth-a bit-depth-b)
              (> res-a res-b))))
        (Display/getAvailableDisplayModes)))

(defn fullscreen-display-modes
  "Returns a seq of fullscreen compatible display modes sorted by
   resolution size with highest resolution first and lowest last."
  []
  (filter #(.isFullscreenCapable ^DisplayMode %) (display-modes)))

(defn undecorate-display!
  "All future display windows will be undecorated (i.e. no title bar)"
  []
  (System/setProperty "org.lwjgl.opengl.Window.undecorated" "true"))

(defn decorate-display!
  "All future display windows will be decorated (i.e. have a title bar)"
  []
  (System/setProperty "org.lwjgl.opengl.Window.undecorated" "false"))

(defn active?
  "Returns true if the shader display is currently running"
  []
  (= :yes (:active @the-window-state)))

(defn inactive?
  "Returns true if the shader display is completely done running."
  []
  (= :no (:active @the-window-state)))

(defn stop
  "Stop and destroy the shader display. Blocks until completed."
  []
  (when (active?)
    (swap! the-window-state assoc :active :stopping)
    (while (not (inactive?))
      (Thread/sleep 100)))
  (remove-watch (:shader-str-atom @the-window-state) :shader-str-watch)
  (stop-watcher @watcher-future)
  )
  


(defn start-shader-display
  "Start a new shader display with the specified mode. Prefer start or
   start-fullscreen for simpler usage."
  [mode shader-filename-or-str-atom textures cams videos title true-fullscreen?
   user-data user-fn display-sync-hz]
  (let [is-filename     (not (instance? clojure.lang.Atom shader-filename-or-str-atom))
        shader-filename (if is-filename
                          shader-filename-or-str-atom)
        ;; Fix for issue 15.  Normalize the given shader-filename to the
        ;; path separators that the system will use.  If user gives path/to/shader.glsl
        ;; and windows returns this as path\to\shader.glsl from .getPath, this
        ;; change should make comparison to path\to\shader.glsl work.
        shader-filename (if (and is-filename (not (nil? shader-filename)))
                          (.getPath (File. ^String shader-filename)))
        shader-str-atom (if-not is-filename
                          shader-filename-or-str-atom
                          (atom nil))
        shader-str      (if-not is-filename
                          @shader-str-atom)]
    (when (sane-user-inputs mode shader-filename shader-str textures title true-fullscreen? user-fn)
      ;; stop the current shader
      (stop)
      ;; start the watchers
      (if is-filename
        (when-not (nil? shader-filename)
          (swap! watcher-future
                 (fn [x] (start-watcher shader-filename))))
        (add-watch shader-str-atom :shader-str-watch watch-shader-str-atom))
      ;; set a global window-state instead of creating a new one
      (reset! the-window-state default-state-values)
      ;; set user data
      (reset! shader-user-data user-data)
      ;; start the requested shader
      (.start (Thread.
               (fn [] (run-thread the-window-state
                                 mode
                                 shader-filename
                                 shader-str-atom
                                 textures
                                 cams
                                 videos
                                 title
                                 true-fullscreen?
                                 user-fn
                                 display-sync-hz)))))))

(defn start
  "Start a new shader display. Forces the display window to be
   decorated (i.e. have a title bar)."
  [shader-filename-or-str-atom
   &{:keys [width height title display-sync-hz
            textures cams videos user-data user-fn]
     :or {width           600
          height          600
          title           "shadertone"
          display-sync-hz 60
          textures        []
          cams            []
          videos          []        
          user-data       {}
          user-fn         shader-default-fn}}]
  (let [mode (DisplayMode. width height)]
    ;(decorate-display!)
    (undecorate-display!)
    (start-shader-display mode shader-filename-or-str-atom textures cams videos title false user-data user-fn display-sync-hz)))

(defn start-fullscreen
  "Start a new shader display in pseudo fullscreen mode. This creates
   a new borderless window which is the size of the current
   resolution. There are therefore no OS controls for closing the
   shader window. Use (stop) to close things manually."
  [shader-filename-or-str-atom
   &{:keys [display-sync-hz textures cams videos user-data user-fn]
     :or {display-sync-hz 60
          textures        [nil]
          cams            []
          videos          []        
          user-data       {}
          user-fn         shader-default-fn}}]
     (let [mode (Display/getDisplayMode)]
       (undecorate-display!)
       (start-shader-display mode shader-filename-or-str-atom textures cams videos "" false user-data user-fn display-sync-hz)))

(defn throw-exceptions-on-gl-errors!
  "When v is true, throw exceptions when glGetError() returns
  non-zero.  This is the default setting.  When v is false, do not
  throw the exception.  Perhaps setting to false during a performance
  will allow you to avoid over-agressive exceptions.  Leave this true
  otherwise."  [v]
  (reset! throw-on-gl-error v))

(defn pixel-read-enable!
  "Enable reading a pixel each frame from location x,y.  Be sure x,y
   are valid or things may crash!  Results are available via the
   function (pixel) and via the atom @pixel-value"
  [x y]
  (swap! the-window-state assoc
         :pixel-read-enable true
         :pixel-read-pos-x  x
         :pixel-read-pos-y  y)
  nil)

(defn pixel-read-disable!
  "Disable reading pixel values each frame."
  []
  (swap! the-window-state assoc
         :pixel-read-enable false)
  nil)

(defn pixel
  "Return the data that was read from the currently drawn frame at the
  x,y location specified in the (pixel-read-enable! x y) call.  When
  enabled, a [red green blue] vector of floating point [0.0,1.0]
  values is returned.  Otherwise, [0.0 0.0 0.0] is returned."
  []
  (if (:pixel-read-enable @the-window-state)
    (get-pixel-value (:pixel-read-data @the-window-state))
    [0.0 0.0 0.0]))
