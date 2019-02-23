(ns #^{:author "Roger Allen"
       :doc "Shadertoy-like core library."}
  shadertone.shader
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [watchtower.core :as watcher]
            [clojure.java.io :as io]
            [while-let.core :as while-let]
            [clojure.core.async
             :as async
             :refer [>! <! >!! <!! go go-loop chan buffer sliding-buffer dropping-buffer close! thread
                     alts! alts!! timeout]]
            clojure.string)
  (:import [org.opencv.core Mat Core CvType]
    [org.opencv.videoio Videoio VideoCapture]
    [org.opencv.video Video]
    [org.opencv.utils.Converters]
    [org.opencv.imgproc Imgproc]
    [org.opencv.imgcodecs Imgcodecs]
    [org.bytedeco.javacpp Pointer]
    [org.bytedeco.javacpp BytePointer]
    [org.bytedeco.javacpp v4l2]
    [org.bytedeco.javacpp Loader]
           ;s(org.bytedeco.javacpp Pointer BytePointer v4l2 Loader)
           (java.awt.image BufferedImage DataBuffer DataBufferByte WritableRaster)
           (java.io File FileInputStream)
           (java.nio IntBuffer ByteBuffer FloatBuffer ByteOrder)
           (java.util Calendar)
           (java.util List)
           (javax.imageio ImageIO)
           (java.lang.reflect Field)
           (org.lwjgl BufferUtils)
           (org.lwjgl.glfw GLFW GLFWErrorCallback GLFWKeyCallback)
           (org.lwjgl.opengl GL GL11 GL12 GL13 GL15 GL20 GL30 GL40)))
;; ======================================================================
;; State Variables
;; a map of state variables for use in the gl thread
(defonce default-state-values
  { :active                  :no  ;; :yes/:stopping/:no
    :width                   0
    :height                  0
    :title                   ""
    :display-sync-hz         30 
    :start-time              0
    :last-time               0
    :drawnFrameCount         (atom 0)
    :elapsedTime             (atom 0)
    :actuaFPS                (atom 0)
    :window                  nil
    :keyCallback             nil
    ;; mouse
;;     :mouse-clicked           false
;;     :mouse-pos-x             0
;;     :mouse-pos-y             0
;;     :mouse-ori-x             0
;;     :mouse-ori-y             0
    ;; geom ids
    :vbo-id                  0
    :vertices-count          0
    ;; shader program
    :shader-good             true ;; false in error condition
    :shader-filename         nil
    :shader-str-atom         (atom nil)
    :shader-str              ""
    :vs-id                   0
    :fs-id                   0
    :pgm-id                  0
    ;; shader uniforms
    :i-resolution-loc        0
    :i-global-time-loc       0
    :i-channel-time-loc      0
;;     :i-mouse-loc             0
    :i-channel-loc           [0 0 0 0]
    ;V4l2 feeds
    :i-cam-loc               [0 0 0 0 0]
    :running-cam             [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :capture-cam             [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
    
    :capture-buffer-cam      [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]       

    :buffer-channel-cam      [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
    :frame-set-cam           [(atom false) (atom false) (atom false) (atom false) (atom false)]  
   
    :buffer-cam              [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :target-cam              [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :text-id-cam             [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :internal-format-cam     [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :format-cam              [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :fps-cam                 [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :width-cam               [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :height-cam              [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :play-mode-cam           [(atom :play) (atom :play) (atom :play) (atom :play) (atom :play)] ;Other keywords, :fixedRange-fw, :fixedRange-bw, :fixedRange
    :buffer-length-cam       [(atom 300) (atom 300) (atom 300) (atom 300) (atom 300)] 
    
    :fixed-buffer-index-cam         [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :active-fixed-buffer-idx-cam    [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :fixed-buffer-ready-cam         [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :fixed-buffer-prepare-cam       [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :fixed-buffer-frames-no-cam     [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :fixed-vec-buffers-cam          [[(atom []) (atom [])(atom []) (atom [])(atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])]]

    ;Video feeds
    :video-elapsed-times     [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :video-buf-elapsed-times [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :i-video-loc             [0 0 0 0 0]
    :running-video           [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :video-no-id             [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
    :capture-video           [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]

    :capture-buffer-video    [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]

    :buffer-channel-video    [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
    :ff-buffer-channel-video [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
    :bf-buffer-channel-video [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
  
    :backwards-buffer-video  [(atom []) (atom []) (atom []) (atom []) (atom [])]
    :forwards-buffer-video   [(atom []) (atom []) (atom []) (atom []) (atom [])]
    :frame-set-video         [(atom false) (atom false) (atom false) (atom false) (atom false)]

    :target-video            [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :text-id-video           [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :internal-format-video   [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :format-video            [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :fps-video               [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :width-video             [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :height-video            [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :frames-video            [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)] 
    :frame-ctr-video         [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :frame-change-video      [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :frame-start-video       [(atom 1) (atom 1) (atom 1) (atom 1) (atom 1)]
    :frame-stop-video        [(atom 2) (atom 2) (atom 2) (atom 2) (atom 2)]
    :frame-paused-video      [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :play-mode-video         [(atom :play) (atom :play) (atom :play) (atom :play) (atom :play)] ;Other keywords, :pause :reverse :buffer-length-cam   
    :buffer-length-video     [(atom 300) (atom 300) (atom 300) (atom 300) (atom 300)]
   
    :fixed-buffer-index      [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :active-fixed-buffer-idx [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :fixed-buffer-ready      [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :fixed-buffer-prepare    [(atom false) (atom false) (atom false) (atom false) (atom false)]
    :fixed-buffer-frames-no  [(atom 0) (atom 0) (atom 0) (atom 0) (atom 0)]
    :fixed-vec-buffers       [[(atom []) (atom [])(atom []) (atom [])(atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])] [(atom []) (atom [])(atom [])(atom []) (atom [])]]

   
    ;Video analysis
    :applyAnalysis-video     [(atom []) (atom []) (atom []) (atom []) (atom [])]
    :redHistogram-video      [(atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256)))]
    :greenHistogram-video    [(atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256)))]
    :blueHistogram-video     [(atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256)))]
   
    ;Camera Analysis
    :applyAnalysis-cam       [(atom []) (atom []) (atom []) (atom []) (atom [])]
    :redHistogram-cam        [(atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256)))]
    :greenHistogram-cam      [(atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256)))]
    :blueHistogram-cam       [(atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256))) (atom (vec (make-array Float/TYPE 256)))]   
    
    ;Data Array
    :dataArray               (vec (make-array Float/TYPE 256))
    :i-dataArray-loc         0
    :dataArrayBuffer            (-> (BufferUtils/createFloatBuffer 256)
                                    (.put (float-array
                                    (vec (make-array Float/TYPE 256))))
                                    (.flip))
    ;Other
    :tex-id-fftwave          0
    :i-fftwave-loc           [0]
   

    ;Previous frame
    :tex-id-previous-frame   0
    :i-previous-frame-loc    [0]
    :save-frames             (atom false)
    :buffer-length-frames    100
    :buffer-channel          (atom nil)
    :buffer-writer           (atom nil)
    :bytebuffer-frame        (atom nil)
    :saveFPS                 (atom 25)
    :save-buffer-filename    (atom "./tmp.avi")
    :frameCount              (atom 0)
   
    ;Test texture
    :tex-id-text-texture     0
    :i-text-loc              [0]
    :target-text-tex          (atom 0)
    :internal-format-text-tex (atom 0)
    :format-text-tex          (atom 0)
    :channels-text-tex        (atom 0)
    :width-text-tex          (atom 0)
    :height-text-tex          (atom 0)
    :text-tex-mat            (atom 0)
    :bytebuffer-text         (atom nil)
   
    :i-channel-res-loc       0
    :i-date-loc              0
    :channel-time-buffer     (-> (BufferUtils/createFloatBuffer 4)
                                    (.put (float-array
                                    [0.0 0.0 0.0 0.0]))
                                    (.flip))
    :channel-res-buffer      (-> (BufferUtils/createFloatBuffer (* 4 12))
                                    (.put (float-array
                                        [0.0 0.0 0.0
                                        0.0 0.0 0.0
                                        0.0 0.0 0.0
                                        0.0 0.0 0.0]))
                                    (.flip))
    ;; textures
    :buffer-tex-channel      [(atom nil) (atom nil) (atom nil) (atom nil) (atom nil)]
    :tex-filenames           []
    :tex-no-id               [nil nil nil nil nil]
    :tex-ids                 []
    :cams                    []
    :videos                  []
    :tex-types               [] ; :cubemap, :previous-frame
    ;; a user draw function
    :user-fn                 nil
    ;; pixel read
    :pixel-read-enable       false
    :pixel-read-pos-x        0
    :pixel-read-pos-y        0
    :pixel-read-data         (-> (BufferUtils/createByteBuffer 3)
                                    (.put (byte-array (map byte [0 0 0])))
                                    (.flip))
                                    
    ; V4l2 output
    :deviceName             (atom "/dev/video3")
    :deviceId               (atom 0)
    :minsize                (atom 0)
    :bff                    (atom 0)
    :isInitialized          (atom false)
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


(defn openV4L2output [device] (let [h        (:height @the-window-state)
                                   w        (:width @the-window-state)
                                   in_fd           (org.bytedeco.javacpp.v4l2/v4l2_open device 02)
                                   cap             (new org.bytedeco.javacpp.v4l2$v4l2_capability)
                                   flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_QUERYCAP) cap)
                                   _               (println "VIDIOC_QUERYCAP: " flag)
                                   v4l2_format     (new org.bytedeco.javacpp.v4l2$v4l2_format)
                                    _               (.type v4l2_format (long org.bytedeco.javacpp.v4l2/V4L2_BUF_TYPE_VIDEO_OUTPUT))
                                    v4l2_pix_format (new org.bytedeco.javacpp.v4l2$v4l2_pix_format)
                                    _               (.pixelformat v4l2_pix_format (long org.bytedeco.javacpp.v4l2/V4L2_PIX_FMT_RGB24))
                                    _               (.width v4l2_pix_format w)
                                    _               (.height v4l2_pix_format h)
                                    minsize         (* 3 (.width v4l2_pix_format))
                                    _               (if (< (.bytesperline v4l2_pix_format) minsize) (.bytesperline v4l2_pix_format minsize))
                                    minsize         (* (.height v4l2_pix_format) (.bytesperline v4l2_pix_format))
                                    _               (if (< (.sizeimage v4l2_pix_format) minsize) (.sizeimage v4l2_pix_format minsize))
                                    _               (.fmt_pix v4l2_format v4l2_pix_format)    
                                    flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_S_FMT) v4l2_format)
                                    _               (println "VIDIOC_S_FMT: " flag)
                                    bff             (new org.bytedeco.javacpp.BytePointer minsize)]
(reset! (:deviceName @the-window-state) device)
(reset! (:deviceId @the-window-state) in_fd)
(reset! (:minsize @the-window-state) minsize)
(reset! (:bff @the-window-state) bff)
(reset! (:isInitialized @the-window-state) true)
))

(defn closeV4L2output [] (org.bytedeco.javacpp.v4l2/v4l2_close @(:deviceId @the-window-state))
                              (reset! (:isInitialized @the-window-state) false))

(defn v4l2test [input output w h] (let [
                                    ;input
                                    in_fd           (org.bytedeco.javacpp.v4l2/v4l2_open input 02)
                                    cap             (new org.bytedeco.javacpp.v4l2$v4l2_capability)
                                    flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_QUERYCAP) cap)
                                    _               (println "VIDIOC_QUERYCAP: " flag)
                                    v4l2_format     (new org.bytedeco.javacpp.v4l2$v4l2_format)
                                    _               (.type v4l2_format (long org.bytedeco.javacpp.v4l2/V4L2_BUF_TYPE_VIDEO_CAPTURE))
                                    v4l2_pix_format (new org.bytedeco.javacpp.v4l2$v4l2_pix_format)
                                    _               (.pixelformat v4l2_pix_format (long org.bytedeco.javacpp.v4l2/V4L2_PIX_FMT_YUV422P))
                                    _               (.width v4l2_pix_format w)
                                    _               (.height v4l2_pix_format h)
                                    minsize         (* 2 (.width v4l2_pix_format))
                                    _               (if (< (.bytesperline v4l2_pix_format) minsize) (.bytesperline v4l2_pix_format minsize))
                                    minsize         (* (.height v4l2_pix_format) (.bytesperline v4l2_pix_format))
                                    _               (if (< (.sizeimage v4l2_pix_format) minsize) (.sizeimage v4l2_pix_format minsize))
                                    _               (.fmt_pix v4l2_format v4l2_pix_format)
                                    flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl in_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_S_FMT) v4l2_format)
                                    _               (println "VIDIOC_S_FMT: " flag)
                                    
                                    bff             (new org.bytedeco.javacpp.BytePointer minsize)
                                    
                                    ;output
                                    out_fd           (org.bytedeco.javacpp.v4l2/v4l2_open output 02)
                                    _               (.type v4l2_format (long org.bytedeco.javacpp.v4l2/V4L2_BUF_TYPE_VIDEO_OUTPUT))
                                    flag            (org.bytedeco.javacpp.v4l2/v4l2_ioctl out_fd (long org.bytedeco.javacpp.v4l2/VIDIOC_S_FMT) v4l2_format)
                                    _               (println "VIDIOC_S_FMT: " flag)
                                    
]
(doseq [x (range 3000)]
    (org.bytedeco.javacpp.v4l2/v4l2_read in_fd bff (long minsize))   
    (org.bytedeco.javacpp.v4l2/v4l2_write out_fd bff (long minsize))  
)
(org.bytedeco.javacpp.v4l2/v4l2_close in_fd)
(org.bytedeco.javacpp.v4l2/v4l2_close out_fd)
)  )

;(org.opencv.imgcodecs.Imgcodecs/imread "./readme_header.jpg")

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

(defn set-dataArray-item [idx val]
    (let [  oa  (:dataArray  @the-window-state)
            na  (assoc oa idx val)]
        (swap! the-window-state assoc :dataArray na)))
        
(defn getWindowState [] (let [ws the-window-state] ws))

;;;;;;;;;;;;;;;;;;;;
;;OPENCV 3 functions
;;;;;;;;;;;;;;;;;;;;
;; (defn oc-initialize-write-to-file [](let[   filename    @(:save-buffer-filename @the-window-state)
;;                                             fourcc      (org.opencv.videoio.VideoWriter/fourcc \D \I \V \X ) ; \M \J \P \G )
;;                                             fps         @(:saveFPS @the-window-state)
;;                                             height      (:height @the-window-state)
;;                                             width       (:width @the-window-state) ;:buffer-writer
;;                                             mat         (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3)
;;                                             vw          (new org.opencv.videoio.VideoWriter filename fourcc fps (.size mat))
;;                                             ;vw          (new org.opencv.videoio.VideoWriter) ;Gstreamer example
;;                                             ;_           (.open vw  "appsrc is-live=true block=true do-timestamp=true ! tee ! video/x-raw,format=BGR, width=1920, height=1080,framerate=30/1 ! queue ! jpegenc ! filesink location=suptest.avi", (org.opencv.videoio.Videoio/CAP_GSTREAMER),  0, 30, (.size mat) true)
;;                                             _           (reset! (:buffer-writer @the-window-state) vw)]))


(defn oc-capture-from-cam [cam-id] (let [           vc  (new org.opencv.videoio.VideoCapture)
                                                    _   (Thread/sleep 200)
                                                    vco (.open vc cam-id)]
                                                    vc))

(defn oc-capture-from-video [video-filename] (let [ vc  (new org.opencv.videoio.VideoCapture) 
                                                    _   (Thread/sleep 200)
                                                    vco (try (.open vc video-filename) (catch Exception e (str "caught exception: " (.getMessage e))))]
                                                    vc))

(defn oc-release [capture] (if (= nil capture) (println "nil camera") (.release capture)))

(defn oc-query-frame [capture buffer] (let [flag (.read capture buffer)]
                                            (if (= flag true) nil (Thread/sleep 100))))

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

;;                                               :format
;;                                               (.set capture org.opencv.videoio.Videoio/CV_CAP_PROP_FORMAT)
                                              
                                              :brightness
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_BRIGHTNESS   val)          
                                              
                                              :contrast
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_CONTRAST   val)          
                                              
                                              :saturation
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_SATURATION   val)          
                                              
                                              :hue
                                              (.set capture org.opencv.videoio.Videoio/CAP_PROP_HUE   val)          
                                              
                                                :default (throw (Exception. "Unknown Property."))))

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
                                              
;;                                               :format
;;                                               (.get capture org.opencv.videoio.Videoio/CV_CAP_PROP_FORMAT)
                                              
                                              :brightness
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_BRIGHTNESS)          
                                              
                                              :contrast
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_CONTRAST)          
                                              
                                              :saturation
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_SATURATION)          
                                              
                                              :hue
                                              (.get capture org.opencv.videoio.Videoio/CAP_PROP_HUE)          
                                              
                                            :default (throw (Exception. "Unknown Property."))))

(defn oc-mat-to-bytebuffer [mat] (let [height      (.height mat)
                                       width       (.width mat)
                                       channels    (.channels mat)
                                       size        (* height width channels)
                                       data        (byte-array size)
                                       _           (.get mat 0 0 data)
                                       ] 
                                       ^ByteBuffer (-> (BufferUtils/createByteBuffer size)
                                              (.put data)
                                              (.flip))
                                              ))

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
  
(defn oc-calc-hist [mat id isVideo]
    (let [  height              (.height mat)
            width               (.width mat)
            pxls                (* height width 1.0)
            pxls                (if (= pxls 0) 1 pxls)
            matList             (java.util.Arrays/asList (object-array [mat]))
            rhistogram          (oc-new-mat)
            ghistogram          (oc-new-mat)
            bhistogram          (oc-new-mat)
            ranges              (new org.opencv.core.MatOfFloat (float-array [0.0 255.0]))
            histSize            (new org.opencv.core.MatOfInt (int-array [256]))
            _                   (org.opencv.imgproc.Imgproc/calcHist matList (new org.opencv.core.MatOfInt (int-array [0])) (new org.opencv.core.Mat) rhistogram histSize ranges)
            _                   (org.opencv.imgproc.Imgproc/calcHist matList (new org.opencv.core.MatOfInt (int-array [1])) (new org.opencv.core.Mat) ghistogram histSize ranges)
            _                   (org.opencv.imgproc.Imgproc/calcHist matList (new org.opencv.core.MatOfInt (int-array [2])) (new org.opencv.core.Mat) bhistogram histSize ranges)
            rFv                 (java.util.ArrayList. (range 256))
            gFv                 (java.util.ArrayList. (range 256))
            bFv                 (java.util.ArrayList. (range 256))]
            (org.opencv.utils.Converters/Mat_to_vector_float (.col rhistogram 0) rFv)
            (org.opencv.utils.Converters/Mat_to_vector_float (.col ghistogram 0) gFv)
            (org.opencv.utils.Converters/Mat_to_vector_float (.col bhistogram 0) bFv)
            (if isVideo 
                (do
                (reset! (nth (:redHistogram-video @the-window-state) id)    (map (partial * (/ 1 pxls) ) rFv))
                (reset! (nth (:greenHistogram-video @the-window-state) id)  (map (partial * (/ 1 pxls) ) gFv))
                (reset! (nth (:blueHistogram-video @the-window-state) id)   (map (partial * (/ 1 pxls) ) bFv)))
                
                (do
                (reset! (nth (:redHistogram-cam @the-window-state) id)      (map (partial * (/ 1 pxls) ) rFv))
                (reset! (nth (:greenHistogram-cam @the-window-state) id)    (map (partial * (/ 1 pxls) ) gFv))
                (reset! (nth (:blueHistogram-cam @the-window-state) id)     (map (partial * (/ 1 pxls) ) bFv))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;Analysis related functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn apply-analysis [mat locals id isVideo] 
    (let [  applyKeyword    (if isVideo (keyword 'applyAnalysis-video) (keyword 'applyAnalysis-cam))
            applies         (vec @(nth (applyKeyword @locals) id))
            ]
            ;(println mat)
            (if (>= (.channels mat) 3)
                (doseq [key applies] (case key 
                                        :histogram  (oc-calc-hist mat id isVideo))))
                nil                        
                                        )) 
(defn toggle-analysis [id isVideo method]     
    (let [  applyKeyword    (if isVideo (keyword 'applyAnalysis-video) (keyword 'applyAnalysis-cam))
            applies         (vec @(nth (applyKeyword @the-window-state) id))
            ]
            (case method
                :histogram
                    (do 
                    (reset! (nth (applyKeyword @the-window-state) id) 
                            (if (and true (=  -1 (.indexOf applies :histogram)))
                                (conj applies :histogram)
                                (vec ( remove #{:histogram} applies)))))
                :histogramAAA
                    (do 
                    (reset! (nth (applyKeyword @the-window-state) id) 
                            (if (and true (=  -1 (.indexOf applies :histogramAAA)))
                                (conj applies :histogramAAA)
                                (vec (remove #{:histogramAAA} applies))))))))
 
 
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;Old single testure handling functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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
                        0))
        _           (assert (pos? image-bytes))] ;; die on unhandled image
    image-bytes))


(defn oc-tex-internal-format
 "return the internal-format for the glTexImage2D call for this image"
 ^Integer
 [image]
 (let [image-type      (.type image)
       internal-format (cond
                        (= image-type org.opencv.core.CvType/CV_8UC3)       GL11/GL_RGB8
                        (= image-type org.opencv.core.CvType/CV_8UC3)       GL11/GL_RGB8
                        (= image-type org.opencv.core.CvType/CV_8UC4)       GL11/GL_RGBA8
                        (= image-type org.opencv.core.CvType/CV_8UC4)       GL11/GL_RGBA8
                        :else GL11/GL_RGB8)]
   internal-format))
  
(defn oc-tex-format
  "return the format for the glTexImage2D call for this image"
  ^Integer
  [image]
  (let [image-type (.type image)
        format     (cond
                    (= image-type org.opencv.core.CvType/CV_8UC3)       GL12/GL_BGR
                    (= image-type org.opencv.core.CvType/CV_8UC3)       GL11/GL_RGB
                    (= image-type org.opencv.core.CvType/CV_8UC4)       GL12/GL_BGRA
                    (= image-type org.opencv.core.CvType/CV_8UC4)       GL11/GL_RGBA
                    :else GL12/GL_BGR)]
    format))  
    
    
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
  (let [videos      (remove nil? videos_in)] 
        (doseq [video-filename-idx (range no-videos)]
            (reset! (nth (:video-no-id @the-window-state) video-filename-idx) (if (= nil (get videos_in video-filename-idx)) nil  video-filename-idx))
            )))
        
      
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
        file-str (str "#version 130\n"
                      "uniform vec3      iResolution;\n"
                      "uniform float     iGlobalTime;\n"
                      "uniform float     iChannelTime[4];\n"
                      "uniform vec3      iChannelResolution[4];\n"
;;                       "uniform vec4      iMouse; \n"
                      (uniform-sampler-type-str tex-types 0)
                      (uniform-sampler-type-str tex-types 1)
                      (uniform-sampler-type-str tex-types 2)
                      (uniform-sampler-type-str tex-types 3)
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
                      "uniform sampler2D iFftWave; \n"
                      "uniform float iDataArray[256]; \n"
                      "uniform sampler2D iPreviousFrame; \n"
                      "uniform sampler2D iText; \n"
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
   (= :previous-frames tex-filename) :previous-frame
   :default :twod))

(defn- init-window
  "Initialise a shader-powered window with the specified
   display-mode. If true-fullscreen? is true, fullscreen mode is
   attempted if the display-mode is compatible. See display-modes for a
   list of available modes and fullscreen-display-modes for a list of
   fullscreen compatible modes.."
  [locals display-mode title shader-filename shader-str-atom tex-filenames cams videos true-fullscreen? user-fn display-sync-hz]
    (when-not (org.lwjgl.glfw.GLFW/glfwInit)
    (throw (IllegalStateException. "Unable to initialize GLFW")))
   
    (let [
   ;;width               (.getWidth ^DisplayMode display-mode)
;;         height              (.getHeight ^DisplayMode display-mode)
        width               (nth display-mode 0) ;(:width @locals) 
        height              (nth display-mode 1);(:height @locals)
        _ (println "AAAAAAAAaa" width height)
        monitor             (org.lwjgl.glfw.GLFW/glfwGetPrimaryMonitor)
        mode                (org.lwjgl.glfw.GLFW/glfwGetVideoMode monitor)
        ;width               1920 ;(.width  mode)
        ;height              1080 ;(.height mode)        
        ;;pixel-format        (PixelFormat.)
;;         context-attributes  (-> (ContextAttribs. 2 1)) ;; GL2.1
        current-time-millis (System/currentTimeMillis)
        tex-filenames       (fill-filenames tex-filenames no-textures)
        videos              (fill-filenames videos no-videos)
        cams                (sort-cams cams)
        _ (println "cams sorted")
        tttt                (sort-videos locals videos)
                _ (println "videos sorted")

        tex-types           (map get-texture-type tex-filenames)
                _ (println "textures sorted")
]
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
        (println "begin shader slurping")
        (let [shader-str (if (nil? shader-filename)
                       @shader-str-atom
                       (slurp-fs locals (:shader-filename @locals)))]
                                 _ (println "finished shader slurping")
                                 )


        (org.lwjgl.glfw.GLFW/glfwDefaultWindowHints)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_VISIBLE org.lwjgl.glfw.GLFW/GLFW_FALSE)
        (org.lwjgl.glfw.GLFW/glfwWindowHint org.lwjgl.glfw.GLFW/GLFW_RESIZABLE org.lwjgl.glfw.GLFW/GLFW_FALSE) 

        (swap! locals assoc
           :window (org.lwjgl.glfw.GLFW/glfwCreateWindow width height title 0 0))
            (when (= (:window @locals) nil)
            (throw (RuntimeException. "Failed to create the GLFW window")))
        (swap! locals assoc
           :keyCallback
           (proxy [GLFWKeyCallback] []
             (invoke [window key scancode action mods]
               (when (and (= key org.lwjgl.glfw.GLFW/GLFW_KEY_ESCAPE)
                          (= action org.lwjgl.glfw.GLFW/GLFW_RELEASE))
                 (org.lwjgl.glfw.GLFW/glfwSetWindowShouldClose (:window @locals) true)))))
        (org.lwjgl.glfw.GLFW/glfwSetKeyCallback (:window @locals) (:keyCallback @locals)) 
        
        (org.lwjgl.glfw.GLFW/glfwMakeContextCurrent (:window @locals))
        (org.lwjgl.glfw.GLFW/glfwSwapInterval 1)
        (org.lwjgl.glfw.GLFW/glfwShowWindow (:window @locals))        
        ;; 
;;         (org.lwjgl.glfw.GLFW/glfwWindowHint (org.lwjgl.glfw.GLFW/GLFW_RESIZABLE  org.lwjgl.glfw.GLFW/GLFW_TRUE))
;;         (org.lwjgl.glfw.GLFW/glfwWindowHint (org.lwjgl.glfw.GLFW/GLFW_CONTEXT_VERSION_MAJOR 2))
;;         org.lwjgl.glfw.GLFW/glfwWindowHint(org.lwjgl.glfw.GLFW/GLFW_CONTEXT_VERSION_MINOR, 1);
;;         org.lwjgl.glfw.GLFW/glfwWindowHint(org.lwjgl.glfw.GLFW/GLFW_OPENGL_PROFILE, org.lwjgl.glfw.GLFW/GLFW_OPENGL_CORE_PROFILE); 
;;         window = GLFW/glfwCreateWindow(width, height, title, 0, 0);
                                 
;;       (swap! locals assoc :shader-str shader-str)
;;                       _ (println "HERE 1" display-mode)
;;                                             _ (println display-mode)
;; 
;;       (Display/setDisplayMode display-mode)
;;                             _ (println "HERE 2")
;; 
;;       (when true-fullscreen?
;;         (Display/setFullscreen true))
;;                               _ (println "HERE 3")
;; 
;;       (Display/setTitle title)
;;                             _ (println "HERE 4")
;; 
;;       (Display/setVSyncEnabled true)
;;                             _ (println "HERE 5")
;; 
;;       (Display/setLocation 0 0)
;;                             _ (println "HERE 6")
;; 
;;       (Display/create pixel-format context-attributes)
;;                 _ (println "finish display")
    

)
      )
      
      

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
        ;_                   (GL20/glVertexAttribPointer 0 4 GL11/GL_FLOAT false 0 0)
        ;_                   (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
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
            i-resolution-loc        (GL20/glGetUniformLocation pgm-id "iResolution")
            i-global-time-loc       (GL20/glGetUniformLocation pgm-id "iGlobalTime")
            i-channel-time-loc      (GL20/glGetUniformLocation pgm-id "iChannelTime")
;;             i-mouse-loc             (GL20/glGetUniformLocation pgm-id "iMouse")
            
            i-channel0-loc          (GL20/glGetUniformLocation pgm-id "iChannel0")
            i-channel1-loc          (GL20/glGetUniformLocation pgm-id "iChannel1")
            i-channel2-loc          (GL20/glGetUniformLocation pgm-id "iChannel2")
            i-channel3-loc          (GL20/glGetUniformLocation pgm-id "iChannel3")

            i-cam0-loc              (GL20/glGetUniformLocation pgm-id "iCam0")
            i-cam1-loc              (GL20/glGetUniformLocation pgm-id "iCam1")
            i-cam2-loc              (GL20/glGetUniformLocation pgm-id "iCam2")
            i-cam3-loc              (GL20/glGetUniformLocation pgm-id "iCam3")
            i-cam4-loc              (GL20/glGetUniformLocation pgm-id "iCam4")
            
            i-video0-loc            (GL20/glGetUniformLocation pgm-id "iVideo0")
            i-video1-loc            (GL20/glGetUniformLocation pgm-id "iVideo1")
            i-video2-loc            (GL20/glGetUniformLocation pgm-id "iVideo2")
            i-video3-loc            (GL20/glGetUniformLocation pgm-id "iVideo3")
            i-video4-loc            (GL20/glGetUniformLocation pgm-id "iVideo4")
    
            i-channel-res-loc       (GL20/glGetUniformLocation pgm-id "iChannelResolution")
            i-date-loc              (GL20/glGetUniformLocation pgm-id "iDate")

            i-fftwave-loc           (GL20/glGetUniformLocation pgm-id "iFftWave")
            
            i-dataArray-loc         (GL20/glGetUniformLocation pgm-id "iDataArray")
            
            i-text-loc              (GL20/glGetUniformLocation pgm-id "iText")
            
            i-previous-frame-loc    (GL20/glGetUniformLocation pgm-id "iPreviousFrame")
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
;;                :i-mouse-loc i-mouse-loc
               :i-channel-loc [i-channel0-loc i-channel1-loc i-channel2-loc i-channel3-loc]
               :i-fftwave-loc [i-fftwave-loc]
               :i-dataArray-loc i-dataArray-loc
               :i-previous-frame-loc [i-previous-frame-loc]
               :i-text-loc           [i-text-loc]
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

           
;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Previous frame functions
;;;;;;;;;;;;;;;;;;;;;;;;;; 
            
(defn- init-frame-tex 
    [locals]    
    (let [  target              (GL11/GL_TEXTURE_2D)
            tex-id              (GL11/glGenTextures)
             width               (:width @locals)
            height              (:height @locals)
            mat                 (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3)
            internal-format     (oc-tex-internal-format mat)
            format              (oc-tex-format mat)            
            buffer              (oc-mat-to-bytebuffer mat)
            _                   (reset! (:bytebuffer-frame @locals) buffer)
            ]
            (swap! locals assoc :tex-id-previous-frame tex-id)  
            (GL11/glBindTexture target tex-id)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)))  

                                                        
;; (defn- process-frame [frame]     
;;             (let [  width       (:width @the-window-state)
;;                     height      (:height @the-window-state)
;;                     mat         (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3)
;;                     mat_flip    mat
;;                     _           (.put mat 0 0 frame)
;;                     _           (org.opencv.core.Core/flip mat mat_flip 0)
;;                     ]
;;                     (org.opencv.imgproc.Imgproc/cvtColor mat_flip mat (org.opencv.imgproc.Imgproc/COLOR_RGB2BGR))
;;                     mat)) 
 
;;;;;;;;;;;;;;;;;;;;;;;;;;
;;Text frame functions
;;;;;;;;;;;;;;;;;;;;;;;;;; 

:target-text-tex
:internal-format-text-tex
:format-text-tex
(defn- init-text-tex 
    [locals]    
    (let [  target              (GL11/GL_TEXTURE_2D)
            tex-id              (GL11/glGenTextures)
            width               (:width @locals)
            height              (:height @locals)
            mat                 (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3)
            internal-format     (oc-tex-internal-format mat)
            format              (oc-tex-format mat)
            channels            (.channels mat)
            buffer              (oc-mat-to-bytebuffer mat)
            _                   (reset! (:bytebuffer-text @locals) buffer)
            ]
            (swap! locals assoc :tex-id-text-texture tex-id) 
            (reset!  (:target-text-tex @locals) target)
            (reset! (:internal-format-text-tex @locals) internal-format)
            (reset! (:format-text-tex @locals) format)
            (reset! (:channels-text-tex @locals) channels)
            (reset! (:width-text-tex @locals) width)
            (reset! (:height-text-tex @locals) height)
            (reset! (:text-tex-mat @locals) mat)
            (GL11/glBindTexture target tex-id)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)))                    
       ;(org.opencv.imgproc.Imgproc/putText tm "kakka" (new org.opencv.core.Point 0 0) (org.opencv.imgproc.Imgproc/FONT_HERSHEY_PLAIN) 1.0 (new org.opencv.core.Scalar 255.0))

(defn write-text 
    [text x y size r g b thickness linetype clear] 
        (let [  width               (:width @the-window-state)
                height              (:height @the-window-state)
                mat                 (if clear (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC3) @(:text-tex-mat @the-window-state))
                corner              (new org.opencv.core.Point x y)
                style               (org.opencv.imgproc.Imgproc/FONT_HERSHEY_TRIPLEX)
                colScal             (new org.opencv.core.Scalar (float r) (float g) (float b))
                _                   (org.opencv.imgproc.Imgproc/putText mat text corner style size colScal thickness linetype)
                buffer              (oc-mat-to-bytebuffer mat)] 
                (if clear nil (reset! (:text-tex-mat @the-window-state) mat))
                (reset! (:bytebuffer-text @the-window-state) buffer))) 
                                        
(defn- set-text-opengl-texture [locals]
   (let[    target              @(:target-text-tex @locals)
            internal-format     @(:internal-format-text-tex @locals)
            format              @(:format-text-tex @locals)
            width               (:width @locals)
            height              (:height @locals)         
            image-bytes         @(:channels-text-tex @locals)
            tex-id              (:tex-id-text-texture @locals)
            tex-image-target    ^Integer (+ 0 target)
            buffer              @(:bytebuffer-text @the-window-state)]           
            (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id))
            (GL11/glBindTexture target tex-id)
            (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
                ^Integer width  ^Integer height 0
                ^Integer format
                GL11/GL_UNSIGNED_BYTE
                buffer))
            (except-gl-errors "@ end of load-texture if-stmt")))
;;;;;;;;;;;;;;;;;;;;;;
;;Save video functions
;;;;;;;;;;;;;;;;;;;;;;            
 
(defn toggle-recording [device] (let [    save    (:save-frames @the-window-state)
                                    ;writer  (:buffer-writer @the-window-state)
                                    ;bfl     (:buffer-length-frames @the-window-state)
                                    ;_       (reset! (:buffer-channel @the-window-state) (async/chan (async/buffer bfl)))
                                    ]                         
                            (if (= false @save) 
                                (do 
                                    (openV4L2output device) 
                                    (println "Start recording")
                                    ;(oc-initialize-write-to-file)
                                    (reset! (:save-frames @the-window-state) true )
                                    ;(start-save-loop-go)
                                    )
                                (do (println "Stop recording")
                                    
                                    (reset! (:save-frames @the-window-state) false )
                                    (closeV4L2output)
                                    ;(stop-save-loop)
                                    (Thread/sleep 100)
                                    ;(.release @writer)
                                    ) 
                                )))   

;;;;;;;;;;;;;;;;;;
;;Camera functions
;;;;;;;;;;;;;;;;;;
(defn- buffer-cam-texture 
    [locals cam-id capture-cam]
    (let [  image               (oc-new-mat)
            imageP              (oc-query-frame capture-cam image)
            _                   (apply-analysis image locals cam-id false)
            cam-buffer          @(nth (:buffer-channel-cam @locals) cam-id)]
            (if (= nil cam-buffer) nil  (async/>!! cam-buffer image))
            ))
     

(defn init-cam-buffer 
   [locals  cam-id] 
   (let [   capture-cam_i        @(nth (:capture-cam @locals) cam-id)
            image                (oc-new-mat)
            imageP               (oc-query-frame capture-cam_i image)
            height               (.height image)
            width                (.width image)
            image-bytes          (.channels image)
            internal-format      (oc-tex-internal-format image)
            format               (oc-tex-format image)
            nbytes               (* image-bytes width height)
            buffer               (oc-mat-to-bytebuffer image)
            _                    (buffer-cam-texture locals cam-id capture-cam_i)
            _ (reset! (nth (:internal-format-cam @locals) cam-id) internal-format)
            _ (reset! (nth (:format-cam @locals) cam-id) format)
            _ (reset! (nth (:width-cam @locals) cam-id) width)
            _ (reset! (nth (:height-cam @locals) cam-id) height)]))    
           
(defn release-cam-textures 
    [cam-id]
    (let [  tmpcams (:cams @the-window-state)]
        (reset! (nth (:running-cam @the-window-state) cam-id) false)
        (swap! the-window-state assoc :cams (assoc tmpcams cam-id nil))
        (println "running-cam at release function after release" (:running-cam @the-window-state))))
           
           
 (defn- init-cam-tex 
    [locals cam-id]    
    (let [  target              (GL11/GL_TEXTURE_2D)
            tex-id              (GL11/glGenTextures)
            height              1
            width               1
            mat                 (org.opencv.core.Mat/zeros height width org.opencv.core.CvType/CV_8UC3)
            internal-format      (oc-tex-internal-format mat)
            format               (oc-tex-format mat)]
            (reset! (nth (:target-cam @locals) cam-id) target)
            (reset! (nth (:text-id-cam @locals) cam-id) tex-id)
            (reset! (nth (:internal-format-cam @locals) cam-id) internal-format)
            (reset! (nth (:format-cam @locals) cam-id) format)
            (reset! (nth (:fps-cam @locals) cam-id) 1)
            (reset! (nth (:width-cam @locals) cam-id) width)
            (reset! (nth (:height-cam @locals) cam-id) height)
            (GL11/glBindTexture target tex-id)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MAG_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_MIN_FILTER GL11/GL_LINEAR)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_S GL11/GL_REPEAT)
            (GL11/glTexParameteri target GL11/GL_TEXTURE_WRAP_T GL11/GL_REPEAT)))
 
(defn- set-cam-opengl-texture [locals cam-id image]
   (let[    target              @(nth (:target-cam @locals) cam-id)
            internal-format     @(nth (:internal-format-cam @locals) cam-id)
            format              @(nth (:format-cam @locals) cam-id)
            height              (.height image)
            width               (.width image)          
            image-bytes         (.channels image)
            tex-id              @(nth (:text-id-cam @locals) cam-id)
            tex-image-target    ^Integer (+ 0 target)
            nbytes              (* width height image-bytes)
            buffer              (oc-mat-to-bytebuffer image)]           
            (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id))
            (GL11/glBindTexture target tex-id)
            (try (GL11/glTexImage2D ^Integer tex-image-target 0 ^Integer internal-format
                ^Integer width  ^Integer height 0
                ^Integer format
                GL11/GL_UNSIGNED_BYTE
                buffer))
            (except-gl-errors "@ end of load-texture if-stmt")))
 

;;;;;;;;;;;;;;;;;;;           
;;;;Camera buffering
;;;;;;;;;;;;;;;;;;;

(defn returnBuffer [bufs idx] (nth bufs idx))

(defn queue-cam [locals cam-id image capture-cam] (let [imageP              (oc-query-frame capture-cam image)
                                                        _                   (apply-analysis image locals cam-id false)
                                                        cam-buffer          @(nth (:buffer-channel-cam @locals) cam-id)]
                                                        (if (= nil cam-buffer) nil  (async/>!! cam-buffer image))
                                                        image))   
(defn record-cam [cam-id buffer_idx] (let [ prepare_buffer          (nth (:fixed-buffer-prepare-cam @the-window-state) cam-id)
                                            active-fixed-buffer-idx (nth (:active-fixed-buffer-idx-cam @the-window-state) cam-id)]
                                            (reset! prepare_buffer true)
                                            (reset! active-fixed-buffer-idx (mod buffer_idx 5))))

(defn set-fixed-cam [cam-id mode]  (cond    (= :play mode) (reset! (nth (:play-mode-cam @the-window-state) cam-id) mode)
                                            (= :static mode) (reset! (nth (:play-mode-cam @the-window-state) cam-id) :fixedRange)
                                            (= :fw mode) (reset! (nth (:play-mode-cam @the-window-state) cam-id) :fixedRange-fw)
                                            (= :bw mode) (reset! (nth (:play-mode-cam @the-window-state) cam-id) :fixedRange-bw))
                                            (println mode))
                                            
(defn set-active-buffer-cam [video-id newIdx] (let [actBuf (nth (:active-fixed-buffer-idx-cam @the-window-state) video-id)
                                     maxIdx 5]
                                    (reset! actBuf (mod newIdx maxIdx))))

                                    
                                    
(defn set-fixed-buffer-index-cam ([cam-id mode] (let [  fixed-buffer-index      (nth (:fixed-buffer-index-cam @the-window-state) cam-id)
                                                        maxBufferLength         @(nth (:buffer-length-cam @the-window-state) cam-id)]
                                                        (cond   (= :inc mode) (do (reset! fixed-buffer-index (mod (inc @fixed-buffer-index) maxBufferLength)))
                                                                (= :dec mode) (do (reset! fixed-buffer-index (mod (dec @fixed-buffer-index) maxBufferLength))))))
                                ( [cam-id mode frame] (let [ fixed-buffer-index      (nth (:fixed-buffer-index-cam @the-window-state) cam-id)
                                                                maxBufferLength         @(nth (:buffer-length-cam @the-window-state) cam-id)]
                                                                (cond   (= :ff mode) (do (reset! fixed-buffer-index (mod frame maxBufferLength)))))))
                                    
(defn- start-cam-loop-thread [locals cam-id]
    (let [  _                       (println "start cam loop " cam-id)
            running-cam_i           @(nth (:running-cam @locals) cam-id)
            capture-cam_i           @(nth (:capture-cam @locals) cam-id)
            playmode                (nth (:play-mode-cam @locals) cam-id)
            prepare_buffer          (nth (:fixed-buffer-prepare-cam @locals) cam-id)
            startTime               (atom (System/nanoTime))
            maxBufferLength         @(nth (:buffer-length-cam @locals) cam-id)
            vec_buffers             (atom (into [] (for [x (range maxBufferLength)]  (oc-new-mat))))
            tmpMat                  (oc-new-mat)
            cam-buffer              @(nth (:buffer-channel-cam @locals) cam-id)
            _                       (doseq [x (range 5)] (reset! (nth (nth (:fixed-vec-buffers-cam @locals) cam-id) x) (into [] (for [x (range maxBufferLength)]  (oc-new-mat))))) 
            fixed_vec_buffers       (nth (:fixed-vec-buffers-cam @locals) cam-id)
            fixed-buffer-index      (nth (:fixed-buffer-index-cam @locals) cam-id)
            active-fixed-buffer-idx (nth (:active-fixed-buffer-idx-cam @locals) cam-id)
            isBuffering             (atom false)
            _ (reset! prepare_buffer true)
            ]
        (if (= true running-cam_i) 
            (do (async/thread  
                (.set @(nth (:capture-cam @locals) cam-id) org.opencv.videoio.Videoio/CAP_PROP_FPS  30.0)
                (while-let/while-let [running @(nth (:running-cam @locals) cam-id)]
                    (reset! startTime (System/nanoTime))
                    (cond (= :play @playmode ) (do
                        ;(reset! (nth (:frame-set-cam @locals) cam-id) true)
                        
                        (if @prepare_buffer (do (doseq [x (range maxBufferLength)] (queue-cam locals cam-id (nth @(nth fixed_vec_buffers @active-fixed-buffer-idx) x) capture-cam_i)) (reset! prepare_buffer false)) 
                                            (do (queue-cam locals cam-id tmpMat capture-cam_i)) )
                        ;(buffer-cam-texture locals cam-id capture-cam_i)
                        ;(reset! (nth (:frame-set-cam @locals) cam-id) nil)
                        )
                        (= :fixedRange-fw @playmode) (do (if (< @fixed-buffer-index (- maxBufferLength 1)) (swap! fixed-buffer-index inc) (reset! fixed-buffer-index 0))
                                                            (async/offer! cam-buffer (nth @(returnBuffer fixed_vec_buffers @active-fixed-buffer-idx) (mod @fixed-buffer-index maxBufferLength)))
                                                            (Thread/sleep  (sleepTime @startTime (System/nanoTime) 30)))
                        (= :fixedRange-bw @playmode) (do (if (< @fixed-buffer-index (- maxBufferLength 1)) (swap! fixed-buffer-index dec) (reset! fixed-buffer-index 0))
                                                            (async/offer! cam-buffer (nth @(returnBuffer fixed_vec_buffers @active-fixed-buffer-idx) (mod @fixed-buffer-index maxBufferLength)))
                                                            (Thread/sleep  (sleepTime @startTime (System/nanoTime) 30)))
                        (= :fixedRange @playmode) (do   (async/offer! cam-buffer (nth @(returnBuffer fixed_vec_buffers @active-fixed-buffer-idx) (mod @fixed-buffer-index maxBufferLength)))
                                                            (Thread/sleep  (sleepTime @startTime (System/nanoTime) (:display-sync-hz @locals))))
                        )
                    )
                (oc-release capture-cam_i)(println "cam loop stopped" cam-id))))))   
                
      
(defn- check-cam-idx 
    [locals cam-id]
    (let [  _                   (println "init cam" cam-id )
            cams_tmp            (:cams @locals) 
            running-cam_i       @(nth (:running-cam @locals) cam-id)
            capture-cam_i       @(nth (:capture-cam @locals) cam-id)] 
            (if (and (not-nil? cam-id) (= false running-cam_i))
                (do (println "cam tb init" cam-id)
                    (reset! (nth (:capture-cam @locals) cam-id) (oc-capture-from-cam cam-id))
                    (println "(nth (:capture-cam @locals) cam-id) " (.isOpened @(nth (:capture-cam @locals) cam-id)))
                    (reset! (nth (:running-cam @locals) cam-id) true)
                    (init-cam-buffer locals cam-id)
                    (if (.isOpened @(nth (:capture-cam @locals) cam-id))
                        (do (start-cam-loop-thread locals cam-id))
                        (do (reset! (nth (:running-cam @locals) cam-id) false)
                            (oc-release capture-cam_i)
                            (swap! locals assoc :cams (set-nil cams_tmp cam-id))
                            (println " bad video " cam-id))))
            (do (println "Unable to init cam: " cam-id) )) ))                           
  
                                            
(defn- get-cam-textures
    [locals cam-id]
    (let [  running-cam_i   @(nth (:running-cam @locals) cam-id)
            image            (async/poll! @(nth (:buffer-channel-cam @locals) cam-id))]
            (if (and (= true running-cam_i) (not (nil? image)))
                (do (set-cam-opengl-texture locals cam-id image)) 
                nil )))
                     
 
(defn- loop-get-cam-textures 
    [locals cams]
    (doseq [i cams]
        (if (= i nil) nil (get-cam-textures locals i))))
          

(defn- init-cams
[locals]
(let [  cam_idxs        (:cams @locals)
        bfl (:buffer-length-cam @locals)]
    (doseq [cam-id (range no-cams)]
        (init-cam-tex locals cam-id)
        ;(println "TODO: the channel creating needs to chekc if a channels exists already")
        (reset! (nth (:buffer-channel-cam @locals) cam-id) (async/chan (async/dropping-buffer 1)))
    )
    (doseq [cam-id cam_idxs]
        (println "cam_id" cam-id)
        (if (= cam-id nil) nil (check-cam-idx locals cam-id)))))    

    

(defn post-start-cam [cam-id] (let [tmpcams (:cams @the-window-state)] 
    (release-cam-textures cam-id)
    (Thread/sleep 10)
    (check-cam-idx the-window-state cam-id)
    (swap! the-window-state assoc :cams (assoc tmpcams cam-id cam-id))))
                                     
;;;;;;;;;;;;;;;;;
;;Video functions
;;;;;;;;;;;;;;;;;

(defn set-video-play [video-id](reset! (nth (:play-mode-video @the-window-state) video-id) :play))

(defn set-video-pause [video-id](reset! (nth (:play-mode-video @the-window-state) video-id) :pause))

(defn set-video-reverse [video-id](reset! (nth (:play-mode-video @the-window-state) video-id) :reverse))

(defn set-video-fixed [video-id mode]  (cond (= :static mode) (reset! (nth (:play-mode-video @the-window-state) video-id) :fixedRange)
                                        (= :fw mode) (reset! (nth (:play-mode-video @the-window-state) video-id) :fixedRange-fw)
                                        (= :bw mode) (reset! (nth (:play-mode-video @the-window-state) video-id) :fixedRange-bw)))


(defn set-video-frame 
    [video-id frame] 
    (let [  frame-count         @(nth (:frames-video @the-window-state) video-id)
            capture-video_i     @(nth (:capture-video @the-window-state) video-id)
            pos                 (oc-get-capture-property :pos-frames capture-video_i)
            frame               (if (< frame 0)  (- frame-count (+ pos  frame)) frame)
            frame-ctr-video     (:frame-ctr-video @the-window-state)]
            (reset! (nth (:frame-ctr-video @the-window-state) video-id) (max (mod frame frame-count) 1 ))
            (reset! (nth (:play-mode-video @the-window-state) video-id) :goto)))
                            
             
(defn set-video-frame-limits 
    [video-id min max] 
    (let [  capture-video_i     @(nth (:capture-video @the-window-state) video-id)
            frame-count         @(nth (:frames-video @the-window-state) video-id)
            min_val             (if  (and (< min max) ( <= min frame-count) (> min 0)) min 1) 
            max_val             (if  (and (< min max) ( <= max frame-count) (> max 0)) max frame-count)
            cur_pos             (oc-get-capture-property :pos-frames capture-video_i )]
            (reset! (nth (:frame-start-video @the-window-state) video-id) min_val)
            (reset! (nth (:frame-stop-video @the-window-state) video-id) max_val)
            (if (< cur_pos min_val) (set-video-frame video-id min_val) (if (> cur_pos max_val) (set-video-frame video-id max_val) 0 ))))
                          

(defn set-video-fps 
    [video-id new-fps] 
    (let [  capture-video_i     @(nth (:capture-video @the-window-state) video-id)
            fps (oc-get-capture-property :fps capture-video_i )
            fpstbs (if (< 0 new-fps) new-fps 1)
            _ (reset! ( nth (:fps-video @the-window-state) video-id) fpstbs)]
            (oc-set-capture-property :fps capture-video_i  fpstbs)
            (println "new fps " fpstbs )))
 
 

(defn- buffer-video-texture 
    [locals video-id capture-video]
    (let [  image               (oc-new-mat)
            imageP              (oc-query-frame capture-video image)
            _                   (apply-analysis image locals video-id true)
            video-buffer          @(nth (:buffer-channel-video @locals) video-id)]
                (if (= nil video-buffer) nil  (async/offer! video-buffer image))
            image))
  
(defn init-video-buffer 
   [locals video-id] 
   (let [  capture-video_i     @(nth (:capture-video @the-window-state) video-id)
           image                (oc-new-mat)
           imageP               (oc-query-frame capture-video_i image)
           height               (.height image)
           width                (.width image)
           image-bytes          (.channels image)
           internal-format      (oc-tex-internal-format image)
           format               (oc-tex-format image)           
           frame-count          (oc-get-capture-property :frame-count  capture-video_i )
           fps                  (oc-get-capture-property :fps capture-video_i)
           nbytes               (* image-bytes width height)
           bff                  (BufferUtils/createByteBuffer nbytes)
           _                    (buffer-video-texture locals video-id capture-video_i)
           _ (reset! (nth (:frame-start-video @locals) video-id)   1 )
           _ (reset! (nth (:frame-stop-video @locals) video-id) frame-count)
           _ (reset! (nth (:internal-format-video @locals) video-id) internal-format)
           _ (reset! (nth (:format-video @locals) video-id) format)
           _ (reset! (nth (:fps-video @locals) video-id) fps)
           _ (reset! (nth (:width-video @locals) video-id) width)
           _ (reset! (nth (:height-video @locals) video-id) height)
           _ (reset! (nth (:frames-video @locals) video-id) frame-count)
           _ (set-video-frame-limits video-id 1 frame-count)]))  
                          
(defn release-video-textures 
    [video-id]
    (let[tmpvideos          (:videos @the-window-state)
        tmp-video-ids       (:video-no-id @the-window-state)]
        (reset! (nth (:running-video @the-window-state) video-id) false)
        (reset! (nth (:video-no-id @the-window-state) video-id) nil)
        (swap! the-window-state assoc :videos (assoc tmpvideos video-id nil))
        (println ":running-video at release function after release" (:running-video @the-window-state))
        (println ":video-no-id at release function after release" (:video-no-id @the-window-state))
        (println ":videos at release function after release" (:videos @the-window-state))))    

      
(defn- init-video-tex 
    [locals video-id]
    (let [  target              (GL11/GL_TEXTURE_2D)
            tex-id              (GL11/glGenTextures)
            height              1
            width               1
            mat                 (org.opencv.core.Mat/zeros  height width org.opencv.core.CvType/CV_8UC4)
            image-bytes         (.channels mat)
            nbytes              (* height width image-bytes)
            internal-format      (oc-tex-internal-format mat)
            format               (oc-tex-format mat)
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
 
        
(defn- set-video-opengl-texture [locals video-id image]
   (let[   target              @(nth (:target-video @locals) video-id)
           internal-format     @(nth (:internal-format-video @locals) video-id)
           format              @(nth (:format-video @locals) video-id)
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
               buffer))
           (except-gl-errors "@ end of load-texture if-stmt")))
           
;;;;;;;;;;;;;;;;;;;           
;;;;Video buffering
;;;;;;;;;;;;;;;;;;;


(defn setActiveBuffer [video-id newIdx] (let [actBuf (nth (:active-fixed-buffer-idx @the-window-state) video-id)
                                     maxIdx 5]
                                    (reset! actBuf (mod newIdx maxIdx))))

               
(defn jumpFrame [capture-video  targetFrame  maxFrame] ( if (and (<  0 targetFrame) (<= targetFrame maxFrame)) 
                                                                        (oc-set-capture-property :pos-frames capture-video  (max (mod targetFrame maxFrame) 0 ))
                                                                        (oc-set-capture-property :pos-frames capture-video  (max (mod (- maxFrame (Math/abs targetFrame) maxFrame) 0 )))))   
                                                                        
(defn bufferSection [video-id active_buffer_idx begin-frame] 
                    ( let [ maxBufferLength     @(nth (:buffer-length-video @the-window-state) video-id)
                            fixed_vec_buffers   (nth (:fixed-vec-buffers @the-window-state) video-id)
                            video-filename      (:videos @the-window-state)
                            video-filename_i    (nth video-filename video-id)
                            capture             (if (not-nil? video-filename_i) (oc-capture-from-video video-filename_i) nil)]
                            (if (not-nil? capture) (do
                            (oc-set-capture-property :pos-frames capture  (max begin-frame 0 ))
                            (doseq [x (range maxBufferLength)]
                                ;(println "assa " active_buffer_idx)
                                (oc-query-frame capture (nth @(returnBuffer fixed_vec_buffers (mod active_buffer_idx 5)) (mod x maxBufferLength )))
                            )
                            (oc-release capture)
                            ))))

(defn set-fixed-buffer-index ([video-id mode] (let [fixed-buffer-index      (nth (:fixed-buffer-index @the-window-state) video-id)
                                                    maxBufferLength         @(nth (:buffer-length-video @the-window-state) video-id)]
                                                    (cond   (= :inc mode) (do (reset! fixed-buffer-index (mod (inc @fixed-buffer-index) maxBufferLength)))
                                                            (= :dec mode) (do (reset! fixed-buffer-index (mod (dec @fixed-buffer-index) maxBufferLength))))))
                            ( [video-id mode frame ] (let [ fixed-buffer-index      (nth (:fixed-buffer-index @the-window-state) video-id)
                                                            maxBufferLength         @(nth (:buffer-length-video @the-window-state) video-id)]
                                                            (cond   (= :ff mode) (do (reset! fixed-buffer-index (mod frame maxBufferLength)))))))
;;;;;;;;;;;;;;;;;;                           
;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;
                                                                        
(defn- start-video-loop 
    [locals video-id]
    (let [  _                       (println "start video loop " video-id)
            capture-video_i         @(nth (:capture-video @locals) video-id)
            _                       (println "capture-video_i " capture-video_i)
            running-video_i         @(nth (:running-video @locals) video-id)
            cur-fps                 @(nth (:fps-video @locals) video-id)
            startTime               (atom (System/nanoTime))
            playmode                (nth (:play-mode-video @locals) video-id)
            maxBufferLength         @(nth (:buffer-length-video @locals) video-id)
            len                     ( int (/ maxBufferLength 2))
            video-buffer            @(nth (:buffer-channel-video @locals) video-id)           
            vec_buffers             [(atom (into [] (for [x (range maxBufferLength)]  (oc-new-mat)))) (atom (into [] (for [x (range maxBufferLength)]  (oc-new-mat))))]
            ;_                       (reset! (nth (nth (:fixed-vec-buffers @locals) video-id) 0) (into [] (for [x (range maxBufferLength)]  (oc-new-mat))))
            ;_                       (reset! (nth (nth (:fixed-vec-buffers @locals) video-id) 1) (into [] (for [x (range maxBufferLength)]  (oc-new-mat)))) 
            _                       (doseq [x (range 5)] (reset! (nth (nth (:fixed-vec-buffers @locals) video-id) x) (into [] (for [x (range maxBufferLength)]  (oc-new-mat))))) 
            fixed_vec_buffers       (nth (:fixed-vec-buffers @locals) video-id)
            fixed-buffer-index      (nth (:fixed-buffer-index @locals) video-id)
            active-fixed-buffer-idx (nth (:active-fixed-buffer-idx @locals) video-id)
            isBuffering             (atom false)
            bufferCtr               (atom 0)
            active_buffer_idx       (atom 0)
            frameCtr                (atom 0)
            previousMode            (atom :play)] 
            (if (= true running-video_i) 
                (do (async/thread
                        (doseq [x (range len)] 
                            (oc-query-frame capture-video_i (nth @(returnBuffer vec_buffers @active_buffer_idx ) x)))
                        (reset! bufferCtr 1)
                        (while-let/while-let [running @(nth (:running-video @locals) video-id)]
                        (reset! startTime (System/nanoTime))
                        (cond 
                            (= :play @playmode) (do (do (if (< @bufferCtr (- maxBufferLength 1)) (swap! bufferCtr inc) (reset! bufferCtr 0) )                                                          
                                                        ;(if (= @previousMode @playmode) nil (do (reset! bufferCtr 0) ))
                                                        (if (and (or (= @bufferCtr 0) (= @bufferCtr len) ) (= @isBuffering false) )
                                                            (do (reset! isBuffering true)
                                                                ;(println "@bufferCtr " @bufferCtr) 
                                                                (let [startFrame @bufferCtr]
                                                                (async/thread
                                                                    (doseq [x (range startFrame (+ startFrame len))]
                                                                        (if (< (oc-get-capture-property :pos-frames capture-video_i ) @(nth (:frame-stop-video @locals) video-id))
                                                                            (oc-query-frame capture-video_i (nth @(returnBuffer vec_buffers @active_buffer_idx ) (mod x maxBufferLength )))
                                                                            (do (reset! frameCtr @(nth (:frame-start-video @locals) video-id))
                                                                                (Thread/sleep 100)
                                                                                (oc-set-capture-property :pos-frames capture-video_i  @(nth (:frame-start-video @locals) video-id)))))
                                                                    (reset! isBuffering false))))
                                                            (do (Thread/sleep 10)))
                                                            (swap! frameCtr inc)
                                                            (reset! previousMode :play)
                                                            (async/offer! video-buffer (nth @(returnBuffer vec_buffers @active_buffer_idx) @bufferCtr)))
                                                    (Thread/sleep  (sleepTime @startTime (System/nanoTime) @(nth (:fps-video @locals) video-id))))                        
                            (= :pause @playmode)(do (async/offer! video-buffer (nth @(returnBuffer vec_buffers @active_buffer_idx) @bufferCtr))
                                                    (Thread/sleep  (sleepTime @startTime (System/nanoTime) @(nth (:fps-video @locals) video-id))))
                            (= :goto @playmode) (do (if (not= (-  (int (oc-get-capture-property :pos-frames capture-video_i)) 1 ) @(nth (:frame-ctr-video @locals) video-id))
                                                    (do (oc-set-capture-property :pos-frames  capture-video_i  @(nth (:frame-ctr-video @locals) video-id))))
                                                        (reset! bufferCtr len)
                                                        (while @isBuffering (Thread/sleep 10))
                                                        (let [startFrame @bufferCtr]
                                                        (async/thread
                                                            (doseq [x (range startFrame (+ startFrame len))]
                                                                (if (< (oc-get-capture-property :pos-frames capture-video_i ) @(nth (:frame-stop-video @locals) video-id))
                                                                    (oc-query-frame capture-video_i (nth @(returnBuffer vec_buffers @active_buffer_idx ) (mod x maxBufferLength )))
                                                                    (do (reset! frameCtr @(nth (:frame-start-video @locals) video-id))
                                                                                    (Thread/sleep 200)
                                                                                    (oc-set-capture-property :pos-frames capture-video_i  @(nth (:frame-start-video @locals) video-id)))))))
                                                    (reset! frameCtr (oc-get-capture-property :pos-frames capture-video_i ))    
                                                    (do (Thread/sleep ( / 1 @(nth (:fps-video @locals) video-id)))(set-video-play video-id)))
                            (= :reverse @playmode)(do (if (> (oc-get-capture-property :pos-frames capture-video_i ) @(nth (:frame-start-video @locals) video-id))                        
                                                             (do
                                                                (if (= @previousMode @playmode) nil (do (reset! bufferCtr 1) ))
                                                                (do (if (< 0 @bufferCtr ) (swap! bufferCtr dec) (reset! bufferCtr (- maxBufferLength 1))) 
                                                                    (async/offer! video-buffer (nth @(returnBuffer vec_buffers @active_buffer_idx) @bufferCtr))
                                                                    (if (and (or (= @bufferCtr 0) (= @bufferCtr len) ) (= @isBuffering false) )
                                                                        (do (reset! isBuffering true)
                                                                            (let [startFrame  @bufferCtr]
                                                                            (async/thread
                                                                                ;(oc-set-capture-property :pos-frames capture-video_i  (- (oc-get-capture-property :pos-frames capture-video_i) (* 1 maxBufferLength) ))
                                                                                (jumpFrame capture-video_i  (- (oc-get-capture-property :pos-frames capture-video_i) (* 1 maxBufferLength) )  @(nth (:frame-stop-video @locals) video-id))
                                                                                (Thread/sleep 200)
                                                                                (doseq [x (range startFrame (+ startFrame len))]
                                                                                    (if (> (oc-get-capture-property :pos-frames capture-video_i ) @(nth (:frame-start-video @locals) video-id))
                                                                                        (oc-query-frame capture-video_i (nth @(returnBuffer vec_buffers @active_buffer_idx ) (mod (+ x 0) maxBufferLength )))
                                                                                        (do     (reset! frameCtr @(nth (:frame-stop-video @locals) video-id))
                                                                                                (Thread/sleep 200)
                                                                                                (do (oc-set-capture-property :pos-frames capture-video_i  @(nth (:frame-stop-video @locals) video-id))))))
                                                                              (reset! isBuffering false)))))
                                                                    (swap! frameCtr dec))
                                                                    (Thread/sleep  (sleepTime @startTime (System/nanoTime) @(nth (:fps-video @locals) video-id))))
                                                             (do  (reset! frameCtr @(nth (:frame-stop-video @locals) video-id))
                                                                    (println "stop frame " @(nth (:frame-stop-video @locals) video-id))
                                                                    (do (oc-set-capture-property :pos-frames capture-video_i  @(nth (:frame-stop-video @locals) video-id)))))
                                                            (reset! previousMode :reverse)
                                                        )
                             (= :fixedRange-fw @playmode)(do    (if (< @fixed-buffer-index (- maxBufferLength 1)) (swap! fixed-buffer-index inc) (reset! fixed-buffer-index 0)) 
                                                                (async/offer! video-buffer (nth @(returnBuffer fixed_vec_buffers @active-fixed-buffer-idx) (mod @fixed-buffer-index maxBufferLength)))
                                                                (Thread/sleep  (sleepTime @startTime (System/nanoTime) @(nth (:fps-video @locals) video-id))))
                             (= :fixedRange-bw @playmode)(do    (if (< @fixed-buffer-index (- maxBufferLength 1)) (swap! fixed-buffer-index dec) (reset! fixed-buffer-index 0)) 
                                                                (async/offer! video-buffer (nth @(returnBuffer fixed_vec_buffers @active-fixed-buffer-idx) (mod @fixed-buffer-index maxBufferLength)))
                                                                (Thread/sleep  (sleepTime @startTime (System/nanoTime) @(nth (:fps-video @locals) video-id))))
                             (= :fixedRange @playmode)(do       (async/offer! video-buffer (nth @(returnBuffer fixed_vec_buffers @active-fixed-buffer-idx) (mod @fixed-buffer-index maxBufferLength)))
                                                                (Thread/sleep  (sleepTime @startTime (System/nanoTime) (:display-sync-hz @locals))))))
                    (oc-release capture-video_i))
                    (println "video loop stopped " video-id)))))   
       
   
       
(defn- check-video-idx 
   [locals video-id]
   (let [   _                   (println "init video" video-id )
            capture-video_i     @(nth (:capture-video @locals) video-id)
            running-video_i     @(nth (:running-video @locals) video-id)
            video-filename      (:videos @locals)
            video-filename_i    (get video-filename video-id)] 
            (if (and (not-nil? video-filename_i) (= false running-video_i)(.exists (io/file video-filename_i)))
                (do (println "video tb init"video-filename_i)
                    (reset! (nth (:capture-video @locals) video-id) (oc-capture-from-video video-filename_i))
                    (reset! (nth (:running-video @locals) video-id) true)
                    (init-video-buffer locals video-id)
                    (if (.isOpened @(nth (:capture-video @locals) video-id))
                        (do (future (start-video-loop locals video-id)))
                        (do (reset! (nth (:running-video @locals) video-id) false)
                            (oc-release capture-video_i)
                            (swap! locals assoc :videos (set-nil video-filename video-id))
                            (println " bad video " video-id))))
               (do (println "Unable to init video: " video-id) ))))   
                
(defn post-start-video 
    [video-filename video-id] 
    (let [  tmpvideo            (:videos @the-window-state)
            tmpvideo_ids        (:video-no-id @the-window-state)] 
            (release-video-textures video-id)
            (Thread/sleep 100)
            (swap! the-window-state assoc :videos (assoc tmpvideo video-id video-filename))
            (reset! (nth (:video-no-id @the-window-state) video-id) video-id)
            (check-video-idx the-window-state video-id)))
 
          
(defn- get-video-textures
    [locals video-id]
    (let [  running-video_i     @(nth (:running-video @locals) video-id)
            capture-video_i     @(nth (:capture-video @locals) video-id)
            ;cur-fps             @(nth (:fps-video @locals) video-id) ;(oc-get-capture-property :fps capture-video_i )
            ;frame-duration      (* 1E9 (/ 1 cur-fps))
            ;cur-time            (System/nanoTime)
            ;elapsed-time        (- cur-time @(nth (:video-elapsed-times @locals) video-id))
            image               (async/poll! @(nth (:buffer-channel-video @locals) video-id))]
            (if (and (= true running-video_i) (not (nil? image)))
                (do (set-video-opengl-texture locals video-id image) 
                    ;(reset! (nth (:video-elapsed-times @locals) video-id)  (System/nanoTime))
                    ) 
                nil)))
                             
(defn- loop-get-video-textures 
    [locals videos]
    (let [noid (:video-no-id @locals)]
                (doseq [i noid]
                (if (= @i nil)  nil (get-video-textures locals @i)))))

                
(defn- init-videos 
    [locals]
    (let [  video_idxs      (:videos @locals)
            ;bufferLength    @(nth (:buffer-length-video @locals) video-id)
            bufferLength    1]
            (doseq [video-id (range no-videos)]
                (init-video-tex locals video-id )
                (reset! (nth (:buffer-channel-video @locals) video-id) (async/chan (async/buffer 5))))
            (doseq [video-id (range no-videos)]
                (println "video_id" video-id)
                ;(reset! (nth (:video-elapsed-times @locals) video-id)  (System/nanoTime) )
                (reset! (nth (:video-buf-elapsed-times @locals) video-id)  (System/nanoTime) )

                (check-video-idx locals video-id))))    
       
;;;;;;;;;;;;;;;;;;;;;;;;;;                                                        
;;GL drawing stuff;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;
      
(defn- init-gl
  [locals]
  (let [{:keys [width height user-fn]} @locals]
    ;;(println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GL/createCapabilities)
    (println "OpenGL version:" (GL11/glGetString GL11/GL_VERSION))
    (GL11/glClearColor 0.0 0.0 0.0 0.0)
    (GL11/glViewport 0 0 width height)
    (init-buffers locals)
    (init-textures locals)

    (init-cams locals)
    (init-videos locals)
    (init-shaders locals)
    (swap! locals assoc :tex-id-fftwave (GL11/glGenTextures))
    (init-frame-tex locals)
    (init-text-tex locals)
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
                i-resolution-loc    (GL20/glGetUniformLocation new-pgm-id "iResolution")
                i-global-time-loc   (GL20/glGetUniformLocation new-pgm-id "iGlobalTime")
                i-channel-time-loc  (GL20/glGetUniformLocation new-pgm-id "iChannelTime")
;;                 i-mouse-loc         (GL20/glGetUniformLocation new-pgm-id "iMouse")
                i-channel0-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel0")
                i-channel1-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel1")
                i-channel2-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel2")
                i-channel3-loc      (GL20/glGetUniformLocation new-pgm-id "iChannel3")
                               
                i-cam0-loc          (GL20/glGetUniformLocation new-pgm-id "iCam0")
                i-cam1-loc          (GL20/glGetUniformLocation new-pgm-id "iCam1")
                i-cam2-loc          (GL20/glGetUniformLocation new-pgm-id "iCam2")
                i-cam3-loc          (GL20/glGetUniformLocation new-pgm-id "iCam3")
                i-cam4-loc          (GL20/glGetUniformLocation new-pgm-id "iCam4")
                
                i-video0-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo0")
                i-video1-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo1")
                i-video2-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo2")
                i-video3-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo3")
                i-video4-loc        (GL20/glGetUniformLocation new-pgm-id "iVideo4")

                i-channel-res-loc   (GL20/glGetUniformLocation new-pgm-id "iChannelResolution")
                i-date-loc          (GL20/glGetUniformLocation new-pgm-id "iDate")
                i-fftwave-loc       (GL20/glGetUniformLocation new-pgm-id "iFftWave")
                i-dataArray-loc     (GL20/glGetUniformLocation new-pgm-id "iDataArray")
                                            
                i-text-loc              (GL20/glGetUniformLocation new-pgm-id "iText")
                
                i-previous-frame-loc    (GL20/glGetUniformLocation new-pgm-id "iPreviousFrame")]
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
;;                    :i-mouse-loc i-mouse-loc
                   :i-channel-loc [i-channel0-loc i-channel1-loc i-channel2-loc i-channel3-loc]
                   :i-fftwave-loc [i-fftwave-loc] 
                   :i-previous-frame-loc [i-previous-frame-loc]
                   :i-text-loc           [i-text-loc]
                   :i-dataArray-loc i-dataArray-loc
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
                      
(defn- draw
  [locals]
  (let [{:keys [width height i-resolution-loc
                start-time last-time i-global-time-loc
                i-date-loc
                pgm-id vbo-id
                vertices-count
;;                 i-mouse-loc
;;                 mouse-pos-x mouse-pos-y
;;                 mouse-ori-x mouse-ori-y
                i-channel-time-loc i-channel-loc i-fftwave-loc i-cam-loc i-video-loc
                i-channel-res-loc i-dataArray-loc i-previous-frame-loc i-text-loc
                channel-time-buffer channel-res-buffer bytebuffer-frame  buffer-channel dataArrayBuffer dataArray
                old-pgm-id old-fs-id
                tex-ids cams text-id-cam videos text-id-video tex-types tex-id-previous-frame tex-id-text-texture
                user-fn
                pixel-read-enable
                pixel-read-pos-x pixel-read-pos-y
                pixel-read-data
                save-frames]} @locals
        cur-time    (/ (- last-time start-time) 1000.0)
        _           (.put ^FloatBuffer channel-time-buffer 0 (float cur-time))
        _           (.put ^FloatBuffer channel-time-buffer 1 (float cur-time))
        _           (.put ^FloatBuffer channel-time-buffer 2 (float cur-time))
        _           (.put ^FloatBuffer channel-time-buffer 3 (float cur-time))
        _          (.flip (.put ^FloatBuffer dataArrayBuffer  (float-array dataArray)))
        
        cur-date    (Calendar/getInstance)
        cur-year    (.get cur-date Calendar/YEAR)         ;; four digit year
        cur-month   (.get cur-date Calendar/MONTH)        ;; month 0-11
        cur-day     (.get cur-date Calendar/DAY_OF_MONTH) ;; day 1-31
        cur-seconds (+ (* (.get cur-date Calendar/HOUR_OF_DAY) 60.0 60.0)
                       (* (.get cur-date Calendar/MINUTE) 60.0)
                       (.get cur-date Calendar/SECOND))]

    (except-gl-errors "@ draw before clear")

    (reset! (:frameCount @locals) (+ @(:frameCount @locals) 1)) 

    (GL11/glClear (bit-or GL11/GL_COLOR_BUFFER_BIT GL11/GL_DEPTH_BUFFER_BIT))
    
    ;(GL20/glUseProgram pgm-id)
     ;(println cur-time @(:frameCount @locals)) 
     ;;;;;;;;;;;;
     ;;;;;;;;;;;;;
     ;;;;;;;;;;;;,
     ;;;;;;;;;;;;;
    (when user-fn
      (user-fn :pre-draw pgm-id (:tex-id-fftwave @locals)))

    ;; activate textures
    (dotimes [i (count tex-ids)]
      (when (nth tex-ids i)
        (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 (nth tex-ids i)))
        (cond
         (= :cubemap (nth tex-types i))
         (GL11/glBindTexture GL13/GL_TEXTURE_CUBE_MAP (nth tex-ids i))
         :default
         (GL11/glBindTexture GL11/GL_TEXTURE_2D (nth tex-ids i)))))
    
    (except-gl-errors "@ draw after activate textures")
    
     (loop-get-cam-textures locals cams)
     (loop-get-video-textures locals videos)
     (set-text-opengl-texture locals)
;; 
;;     ;; setup our uniform
    (GL20/glUniform3f i-resolution-loc width height 1.0)
    (GL20/glUniform1f i-global-time-loc cur-time)
;;    (GL20/glUniform1i  ^Integer i-channel-time-loc ^FloatBuffer channel-time-buffer)
;; 
;; ;;     (GL20/glUniform4f i-mouse-loc
;; ;;                       mouse-pos-x
;; ;;                       mouse-pos-y
;; ;;                       mouse-ori-x
;; ;;                       mouse-ori-y)
;;     ;(println "i-video-loc" i-video-loc)
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
    (GL20/glUniform1i (nth i-text-loc 0) 16)
;; 
;;     (GL20/glUniform3fv  ^Integer i-channel-res-loc ^FloatBuffer channel-res-buffer)
;;     (GL20/glUniform4f i-date-loc cur-year cur-month cur-day cur-seconds)
;;     (GL20/glUniform1i  ^Integer i-dataArray-loc ^FloatBuffer dataArrayBuffer)
;;     
;;     ;; get vertex array ready
     (GL11/glEnableClientState GL11/GL_VERTEX_ARRAY)
     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER vbo-id)
     (GL11/glVertexPointer 4 GL11/GL_FLOAT 0 0)
;; 
     (except-gl-errors "@ draw prior to DrawArrays")
;; 
;;     ;; Draw the vertices
     (GL11/glDrawArrays GL11/GL_TRIANGLES 0 vertices-count)
;;     
     (except-gl-errors "@ draw after DrawArrays")
;;     
;;     ;; Put everything back to default (deselect)
     (GL15/glBindBuffer GL15/GL_ARRAY_BUFFER 0)
     (GL11/glDisableClientState GL11/GL_VERTEX_ARRAY)
    ;; unbind textures
    (doseq [i (remove nil? tex-ids)]
        (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
        (GL11/glBindTexture GL13/GL_TEXTURE_CUBE_MAP 0)
        (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))

    ;cams
    (dotimes [i (count text-id-cam)]
        (when (nth text-id-cam i)
        (if (= nil @(nth (:frame-set-cam @locals) i))
            (do (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 i))
            (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)) 
            nil )))
    ;videos
    (dotimes [i (count text-id-video)]
        (when (nth text-id-video i)
            (if (= nil @(nth (:frame-set-video @locals) i))
                (do (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 @(nth text-id-video i)))
                    (GL11/glBindTexture GL11/GL_TEXTURE_2D 0))
                nil)))
    
;;     ;text texture :tex-id-text-texture
;;     (do
;;         (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-text-texture))
;;         (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
;;     )
        
    (except-gl-errors "@ draw prior to post-draw")

    (when user-fn
      (user-fn :post-draw pgm-id (:tex-id-fftwave @locals)))
;; 
      (GL20/glUseProgram 0)
    (except-gl-errors "@ draw after post-draw")
                ;Copying the previous image to its own texture            
    (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-previous-frame))
    (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id-previous-frame)
    (GL11/glCopyTexImage2D GL11/GL_TEXTURE_2D 0 GL11/GL_RGB 0 0 width height 0)
    (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
    (if @save-frames
        (do ; download it
                ;Copying the previous image to its own texture

            
            (GL13/glActiveTexture (+ GL13/GL_TEXTURE0 tex-id-previous-frame))
            (GL11/glBindTexture GL11/GL_TEXTURE_2D tex-id-previous-frame)
            (GL11/glGetTexImage GL11/GL_TEXTURE_2D 0 GL11/GL_RGB GL11/GL_UNSIGNED_BYTE  ^ByteBuffer @bytebuffer-frame)
            (GL11/glBindTexture GL11/GL_TEXTURE_2D 0)
            ;@(:bff @the-window-state)
            (org.bytedeco.javacpp.v4l2/v4l2_write @(:deviceId @the-window-state) (new org.bytedeco.javacpp.BytePointer @bytebuffer-frame) (long  @(:minsize @the-window-state)))
            ; and save it to a video to a file
            ;(buffer-frame locals @bytebuffer-frame)
            )
          nil)
                    
    (except-gl-errors "@ draw after copy")
    
    ;; read a pixel value
    (when pixel-read-enable
      (GL11/glReadPixels ^Integer pixel-read-pos-x ^Integer pixel-read-pos-y
                        1 1
                        GL11/GL_RGB GL11/GL_UNSIGNED_BYTE
                        ^ByteBuffer pixel-read-data)
      (except-gl-errors "@ draw after pixel read")
      (reset! pixel-value (get-pixel-value ^ByteBuffer pixel-read-data)))
      
      ))
      
(defn- update-and-draw
  [locals]
  (let [{:keys [width height last-time pgm-id
;;                 mouse-pos-x mouse-pos-y
;;                 mouse-clicked mouse-ori-x mouse-ori-y
                ]} @locals
                cur-time (System/currentTimeMillis)
                ;starttime (System/nanoTime)
;;                 cur-mouse-clicked (Mouse/isButtonDown 0)
;;                 mouse-down-event (and cur-mouse-clicked (not mouse-clicked))
;;                 cur-mouse-pos-x (if cur-mouse-clicked (Mouse/getX) mouse-pos-x)
;;                 cur-mouse-pos-y (if cur-mouse-clicked (Mouse/getY) mouse-pos-y)
;;                 cur-mouse-ori-x (if mouse-down-event
;;                           (Mouse/getX)
;;                           (if cur-mouse-clicked
;;                             mouse-ori-x
;;                             (- (Math/abs ^float mouse-ori-x))))
;;                 cur-mouse-ori-y (if mouse-down-event
;;                           (Mouse/getY)
;;                           (if cur-mouse-clicked
;;                             mouse-ori-y
;;                             (- (Math/abs ^float mouse-ori-y))))
                            ]
    (swap! locals
           assoc
           :last-time cur-time
;;            :mouse-clicked cur-mouse-clicked
;;            :mouse-pos-x cur-mouse-pos-x
;;            :mouse-pos-y cur-mouse-pos-y
;;            :mouse-ori-x cur-mouse-ori-x
;;            :mouse-ori-y cur-mouse-ori-y
           )
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
    ;Stop and release video release-cam-textures
    (println " Videos tbd" (:videos @the-window-state))
    (doseq [i (:video-no-id @the-window-state)]
        (if (= @i nil) (println "no video")  (do (release-video-textures @i))))
    (swap! locals assoc :videos (vec (replicate no-videos nil)))
    ;stop recording
    ;(if @(:save-frames @locals) (toggle-recording  @(:device @the-window-state)))
    (closeV4L2output)
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
  (reset! (:frameCount @locals) 0) 

  (let [startTime               (atom (System/nanoTime))]

  (while (and (= :yes (:active @locals))
              (not (org.lwjgl.glfw.GLFW/glfwWindowShouldClose (:window @locals))))
    
    ;(time (do
    (reset! startTime (System/nanoTime))
    (update-and-draw locals)
    ;(Display/update)
    ;(Display/sync (:display-sync-hz @locals))
    (org.lwjgl.glfw.GLFW/glfwSwapBuffers (:window @locals))
    (org.lwjgl.glfw.GLFW/glfwPollEvents)
    (Thread/sleep  (sleepTime @startTime (System/nanoTime) display-sync-hz))
    ;(write-text (str (- (System/nanoTime) @startTime) ) 300 800 10 100 100 0 50 1 true)
    ;(org.lwjgl.glfw.GLFW/glfwPostEmptyEvent)

    ;))
    )
  (destroy-gl locals)
  ;(Display/destroy)
  (org.lwjgl.glfw.GLFW/glfwDestroyWindow (:window @locals))
  (org.lwjgl.glfw.GLFW/glfwTerminate)
  (swap! locals assoc :active :no)

  )
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
  [shader-filename shader-str textures title true-fullscreen? user-fn]
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

;; (defn display-modes
;;   "Returns a seq of display modes sorted by resolution size with highest
;;    resolution first and lowest last."
;;   []
;;   (sort (fn [^DisplayMode a ^DisplayMode b]
;;           (let [res-a       (* (.getWidth a)
;;                                (.getHeight a))
;;                 res-b       (* (.getWidth b)
;;                                (.getHeight b))
;;                 bit-depth-a (.getBitsPerPixel a)
;;                 bit-depth-b (.getBitsPerPixel b) ]
;;             (if (= res-a res-b)
;;               (> bit-depth-a bit-depth-b)
;;               (> res-a res-b))))
;;         (Display/getAvailableDisplayModes)))
;; 
;; (defn fullscreen-display-modes
;;   "Returns a seq of fullscreen compatible display modes sorted by
;;    resolution size with highest resolution first and lowest last."
;;   []
;;   (filter #(.isFullscreenCapable ^DisplayMode %) (display-modes)))

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
    (when (sane-user-inputs shader-filename shader-str textures title true-fullscreen? user-fn)
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
     :or {width           1920
          height          1080
          title           "viritystone"
          display-sync-hz 30
          textures        []
          cams            []
          videos          []        
          user-data       {}
          user-fn         shader-default-fn}}]
   (let [;;mode (Display/getDisplayMode)
         ;;mode (DisplayMode. width height)
        mode  [width height]]
    ;(decorate-display!)
    (undecorate-display!)
    (start-shader-display mode shader-filename-or-str-atom textures cams videos title false user-data user-fn display-sync-hz)))

    
(defn start-fullscreen
  "Start a new shader display. Forces the display window to be
   decorated (i.e. have a title bar)."
  [shader-filename-or-str-atom
   &{:keys [width height title display-sync-hz
            textures cams videos user-data user-fn]
     :or {width           1920
          height          1080
          title           "viritystone"
          display-sync-hz 30
          textures        []
          cams            []
          videos          []        
          user-data       {}
          user-fn         shader-default-fn}}]
   (let [;;mode (Display/getDisplayMode)
         ;;mode (DisplayMode. width height)
        mode  []]
    ;(decorate-display!)
    (undecorate-display!)
;;     (swap! locals
;;            assoc
;;            :width           width
;;            :height          height)
    (start-shader-display mode shader-filename-or-str-atom textures cams videos title false user-data user-fn display-sync-hz)))    
    
;; (defn start-fullscreen
;;   "Start a new shader display in pseudo fullscreen mode. This creates
;;    a new borderless window which is the size of the current
;;    resolution. There are therefore no OS controls for closing the
;;    shader window. Use (stop) to close things manually."
;;   [shader-filename-or-str-atom
;;    &{:keys [display-sync-hz textures cams videos user-data user-fn]
;;      :or {display-sync-hz 60
;;           textures        [nil]
;;           cams            []
;;           videos          []        
;;           user-data       {}
;;           user-fn         shader-default-fn}}]
;;      (let [mode (Display/getDisplayMode)]
;;        (undecorate-display!)
;;        (start-shader-display mode shader-filename-or-str-atom textures cams videos "" false user-data user-fn display-sync-hz)))

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
