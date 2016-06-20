package com.joker.weixinimagedemo.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;
import android.util.DisplayMetrics;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 加载图片的类,单例模式
 * Created by Joker on 2016/6/20.
 */
public class ImageLoader {
    private static ImageLoader loader;
    /**
     * 图片缓存的核心对象
     */
    private LruCache<String, Bitmap> mLruCache;

    /**
     * 线程池
     */
    private ExecutorService mThreadPool;
    /**
     * 默认线程数
     */
    private static final int DEFAULT_THREAD_COUNT = 1;
    /**
     * 队列的调度方式
     */
    private Type mType = Type.LIFO;
    /**
     * 任务队列
     */
    private LinkedList<Runnable> mTaskQueue;
    /**
     * 后台轮询线程
     */
    private Thread mPoolThread;
    private Handler mPoolThreadHandler;
    /**
     * 信号量
     */
    private Semaphore mSemaphorePoolThreadHandler = new Semaphore(0);

    private Semaphore mSemaphoreThreadPool;

    /**
     * UI线程的Handler
     */
    private Handler mUIHandler;
    public enum Type{
        LIFO, FIFO;
    }
    private ImageLoader(int threadCount, Type type){
        inti(threadCount, type);
    }

    public static ImageLoader getInstance(){
        if (loader == null){
            synchronized (ImageLoader.class){
                if (loader == null){
                    loader = new ImageLoader(DEFAULT_THREAD_COUNT, Type.LIFO);
                }
            }
        }
        return loader;
    }

    public static ImageLoader getInstance(int threadCount, Type type){
        if (loader == null){
            synchronized (ImageLoader.class){
                if (loader == null){
                    loader = new ImageLoader(threadCount, type);
                }
            }
        }
        return loader;
    }

    /**
     * 初始化操作
     * @param threadCount
     * @param type
     */
    private void inti(int threadCount, Type type) {
        // 后台轮询线程
        mPoolThread = new Thread(){
            @Override
            public void run() {
                Looper.prepare();
                mPoolThreadHandler = new Handler(){
                    @Override
                    public void handleMessage(Message msg) {
                        // 通过线程池取出一个任务
                        mThreadPool.execute(getTask());

                        try {
                            mSemaphoreThreadPool.acquire();
                        } catch (InterruptedException e) {

                        }
                    }
                };
                // 释放一个信号量
                mSemaphorePoolThreadHandler.release();
                Looper.loop();
            }
        };
        mPoolThread.start();

        // 获取应用的最大内存, 并初始化LruCache
        int maxMemory = (int) Runtime.getRuntime().maxMemory();
        int cacheMemory = maxMemory/8;
        mLruCache = new LruCache<String, Bitmap>(cacheMemory){
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight();
            }
        };

        // 创建线程池
        mThreadPool = Executors.newFixedThreadPool(threadCount);
        mTaskQueue = new LinkedList<Runnable>();
        mType = type;

        mSemaphoreThreadPool = new Semaphore(threadCount);
    }

    /**
     * 从任务队列取出一个方法
     * @return
     */
    public Runnable getTask() {
        if (mType == Type.FIFO){
            return mTaskQueue.removeFirst();
        }else if (mType == Type.LIFO){
            return mTaskQueue.removeLast();
        }
        return null;
    }

    /**
     * 加载显示图片
     * @param path
     * @param imageView
     */
    public void loadImage(final String path, final ImageView imageView){
        // 防止调用多次和图片错位,给ImageView设置一个tag
        imageView.setTag(path);
        if (mUIHandler == null){
            mUIHandler = new Handler(){
                @Override
                public void handleMessage(Message msg) {
                    // 获取得到的图片,为ImageView回调设置图片
                    ImgBeanHolder holder = (ImgBeanHolder) msg.obj;
                    Bitmap bm = holder.bitmap;
                    ImageView imageView = holder.imageView;
                    String path = holder.path;
                    // 判断之前设置的path和获得的path是否是同一个
                    if (imageView.getTag().toString().equals(path)){
                        imageView.setImageBitmap(bm);
                    }
                }
            };
        }

        // 根据url在缓存中获取bitmap对象
        Bitmap bm = getBitmapFromLruCache(path);
        if (bm != null){
            refreshBitmap(path, imageView, bm);
        }else {
            addTasks(new Runnable() {
                @Override
                public void run() {
                    // 加载图片
                    // 图片的压缩
                    // 1. 获取图片要显示的大小
                    ImageSize imageSize = getImageViewSize(imageView);
                    // 2. 压缩图片
                    Bitmap bm = decodeSampledBitmapFromPath(path, imageSize.width, imageSize.height);
                    // 3. 把图片加入到缓存中
                    addBitmapToLruCache(path, bm);
                    // 刷新imageview
                    refreshBitmap(path, imageView, bm);

                    mSemaphoreThreadPool.release();
                }
            });
        }
    }

    /**
     * 刷新ImageView的图片
     * @param path
     * @param imageView
     * @param bm
     */
    private void refreshBitmap(String path, ImageView imageView, Bitmap bm) {
        Message message = Message.obtain();
        ImgBeanHolder holder = new ImgBeanHolder();
        holder.bitmap = bm;
        holder.imageView = imageView;
        holder.path = path;
        message.obj = holder;
        mUIHandler.sendMessage(message);
    }

    /**
     * 获取图片显示的大小
     * @param imageView
     * @return ImageSize中携带了图片的宽和高
     */
    private ImageSize getImageViewSize(ImageView imageView) {
        ImageSize imageSize = new ImageSize();
        // 获取屏幕的宽度
        DisplayMetrics metrics = imageView.getContext().getResources().getDisplayMetrics();
        ViewGroup.LayoutParams lp = imageView.getLayoutParams();
//        int width = (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT? 0 : imageView.getWidth());
        int width = imageView.getWidth();
        if (width <= 0){
            width = lp.width; // 获取imageview在layout中声明的宽度
        }
        if (width <= 0){
           // width = imageView.getMaxWidth(); // 检查最大值
            width = getImageViewFieldValue(imageView, "mMaxWidth");
        }
        if (width <= 0){
            width = metrics.widthPixels;
        }

        int height = imageView.getHeight();
        if (height <= 0){
            height = lp.height; // 获取imageview在layout中声明的宽度
        }
        if (height <= 0){
            //height = imageView.getMaxHeight(); // 检查最大值
            height = getImageViewFieldValue(imageView, "mMaxHeight");
        }
        if (height <= 0){
            height = metrics.heightPixels;
        }

        // 把宽和高设置给imageSize
        imageSize.width = width;
        imageSize.height = height;

        return imageSize;
    }

    /**
     * 通过反射获取ImageView的某个属性值
     * @param object
     * @param fieldName
     * @return
     */
    private static int getImageViewFieldValue(Object object, String fieldName){
        int value = 0;
        try {
            Field field = ImageView.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            int fieldValue = field.getInt(object);
            if (fieldValue > 0 && fieldValue < Integer.MAX_VALUE){
                value = fieldValue;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return value;
    }

    /**
     * 对图片进行压缩操作, 通过options对图片进行压缩
     * @param path
     * @param width
     * @param height
     * @return
     */
    private Bitmap decodeSampledBitmapFromPath(String path, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        // 不加载图片到内存,仅仅是获得图片的宽和高
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path,options);
        options.inSampleSize = caculateInSampleSize(options, width, height);
        // 使用获取得到的inSampleSize再次解析图片
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(path, options);
        return bitmap;
    }

    /**
     * 根据需求的宽和高以及图片实际的宽和高计算sampleSize
     * @param options
     * @param reqWwidth
     * @param reqHeight
     * @return
     */
    private int caculateInSampleSize(BitmapFactory.Options options, int reqWwidth, int reqHeight) {
        int width = options.outWidth;
        int height = options.outHeight;

        int inSampleSize = 1;
        if (width > reqWwidth || height > reqHeight){
            int widthRadio = Math.round(width * 1.0f / reqWwidth);
            int heightRadio = Math.round(height * 1.0f / reqHeight);
            // 获取大的值
            inSampleSize = Math.max(widthRadio, heightRadio);
        }
        return inSampleSize;
    }

    /**
     * 将图片添加到缓存中
     * @param path
     * @param bm
     */
    private void addBitmapToLruCache(String path, Bitmap bm) {
        if (getBitmapFromLruCache(path) == null){
            if (bm != null){
                mLruCache.put(path, bm);
            }
        }
    }


    private synchronized void addTasks(Runnable runnable) {
        mTaskQueue.add(runnable);
        try {
            if (mPoolThreadHandler == null)
            mSemaphorePoolThreadHandler.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mPoolThreadHandler.sendEmptyMessage(0);
    }

    /**
     * 根据path在LruCache中获取bitmap对象
     * @param key
     * @return
     */
    private Bitmap getBitmapFromLruCache(String key) {
        return mLruCache.get(key);
    }

    /**
     * 加载图片时,message中需要传递的数据
     */
    private class ImgBeanHolder{
        Bitmap bitmap;
        ImageView imageView;
        String path;
    }

    private class ImageSize{
        int width;
        int height;
    }

}
