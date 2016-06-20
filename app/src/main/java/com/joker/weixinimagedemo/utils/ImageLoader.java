package com.joker.weixinimagedemo.utils;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.util.LruCache;

import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

                    }
                };
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
    }
}
