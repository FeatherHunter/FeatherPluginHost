package com.hao.featherpluginhost;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import dalvik.system.DexClassLoader;
import dalvik.system.PathClassLoader;

public class HostApplication extends Application{

        /**
         * 尽早进行插件的加载。可以在Application中也可以在Activity的attachBaseContext()方法中调用
         */
        @Override
        protected void attachBaseContext(Context newBase) {
            super.attachBaseContext(newBase);
            try {
                // 1. 加载插件apk(将插件apk中所有dex文件放入到宿主的pathList中的dexElements中)
                loadPluginApk("plugin.apk");
                // 2. Hook ActivityManager: 将插件apk的目标Activity，替换为占坑Activity
                hookActivityManager();
                // 3. Hook ActivityThread中的Handler H。将占坑Acitivty，替换为目标Activity。
                hookActivityThreadHandlerH();
                // 4. 创建属于插件的资源库
                preparePluginResources("plugin.apk");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    /**================================================
     * @function: 加载插件apk到宿主中
     *  1. 下载Apk到宿主应用的数据目录
     *  2. 加载插件Apk的Dex文件
     *================================================*/
    private void loadPluginApk(String pluginApkName) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {

        /**=======================================================
         * 0、模拟下载插件的过程。
         *    1. 从assets/external中将插件apk，移动到，app的数据目录下
         *======================================================*/
        // 1. apk名
        String apkName = pluginApkName;
        // 2. apk目前位于assets/external/路径中
        String apkAssetsPath = "external" + File.separator + apkName;
        // 3、如果/data/data/webview.apk文件已经存在，需要删除后重新复制(模拟下载流程)
        String pluginFilePath = getFilesDir().getAbsolutePath() + File.separator + apkName;
        File pluginFile = new File(pluginFilePath);
        if(!pluginFile.exists()){
            // 4、不存在插件apk时，将assets/external中的文件复制到app的文件下
            copyAssetsFileToAppFiles(apkAssetsPath, apkName);
        }
        // 5、安装。如果数据目录下不存在该插件apk文件，报错。
        if(!pluginFile.exists()){
            Log.e("feather", "Download plugin failed!");
            return;
        }

        /**=====================================================
         * 1、获取到插件Apk的路径
         *    1. 可以从外部存储获取
         *    2. 可以从网络上下载到本地，然后从这个本地地址中获取【本次是模拟网络下载的过程】
         *=====================================================*/
        String pluginApkPath = pluginFilePath;
        // String pluginApkPath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + pluginApkName;

        /**=====================================================
         * 2、创建我们的DexClassLoader来加载“插件Apk的dex文件”
         *    1. 可以从外部存储获取
         *    2. 可以从assets\plugin中获取【就从此处取，毕竟是Demo】
         *    3. 可以从网络上下载到本地，然后从这个本地地址中获取
         *=====================================================*/
        String cachePath = getCacheDir().getAbsolutePath(); // 获取到缓存目录
        DexClassLoader pluginDexClassLoader = new DexClassLoader(pluginApkPath, // apk or jar包的地址
                cachePath,  // optimizedDirectory，经过优化的dex文件（odex）文件输出目录。
                cachePath,  // librarySearchPath，包含native library的目录。（将被添加到app动态库搜索路径列表中）
                getClassLoader()); // 用宿主的ClassLoader作为父类加载器

        /**=====================================================
         * 3、加载“插件Apk”的Dex文件(一): 获取宿主和插件的Dex路径列表: DexPathList pathList
         *    1. 可以从外部存储获取
         *    2. 可以从assets\plugin中获取【就从此处取，毕竟是Demo】
         *    3. 可以从网络上下载到本地，然后从这个本地地址中获取
         *=====================================================*/
        // 1、拿到宿主的ClassLoader
        PathClassLoader hostPathLoader = (PathClassLoader) getClassLoader();
        // 2、反射出BaseDexClassLoader的字段pathList(DexPathList)
        Class<?> baseDexClassLoaderClass = Class.forName("dalvik.system.BaseDexClassLoader");
        Field pathListField = baseDexClassLoaderClass.getDeclaredField("pathList"); // 具有成员变量：DexPathList pathList
        pathListField.setAccessible(true);
        // 3、获取到宿主的pathList
        Object hostPathList = pathListField.get(hostPathLoader); // 从【宿主】的类加载器中，获取到DexPathList pathList
        // 4、获取到插件的pathList
        Object pluginPathList = pathListField.get(pluginDexClassLoader); // 从【插件】的类加载器中，获取到DexPathList pathList

        /**=====================================================
         * 4、加载“插件Apk”的Dex文件(二): 合并宿主和插件的pathList中的dexElements数组
         *=====================================================*/
        // 1、获取到DexPathList的字段dexElements
        Field dexElementsField = hostPathList.getClass().getDeclaredField("dexElements");
        dexElementsField.setAccessible(true);
        // 2、获取到【宿主】的dex数组
        Object hostDexElements = dexElementsField.get(hostPathList);
        // 3、获取到【插件】的dex数组
        Object pluginDexElements = dexElementsField.get(pluginPathList);
        // 4、合并数组
        Class<?> localClass = hostDexElements.getClass().getComponentType(); //获取Host数组类型
        int hostArrayLength = Array.getLength(hostDexElements); //获取Host数组长度
        int newArrayLength = hostArrayLength + Array.getLength(pluginDexElements); //宿主数组size + 插件数组size
        Object newDexElements = Array.newInstance(localClass, newArrayLength); //创建新的数组
        for (int i = 0; i < newArrayLength; ++i) { //将宿主和插件的dex文件设置到新数组中
            if (i < hostArrayLength) {
                Array.set(newDexElements, i, Array.get(hostDexElements, i)); // 先存入宿主的数组元素
            } else {
                Array.set(newDexElements, i, Array.get(pluginDexElements, i - hostArrayLength)); // 再存入插件的数组元素
            }
        }

        /**=====================================================
         * 5、加载“插件Apk”的Dex文件(三): 将新的Dex数组放入到【宿主】的pathList中
         *=====================================================*/
        dexElementsField.set(hostPathList, newDexElements);
    }


    public void hookActivityManager() throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
        // 1、获取ActivityManager类
        Class<?> activityManagerClass = Class.forName("android.app.ActivityManager");
        // 2、取出IActivityManagerSingleton
        Field iActivityManagerSingletonField = activityManagerClass.getDeclaredField("IActivityManagerSingleton");
        iActivityManagerSingletonField.setAccessible(true);
        Object activityManagerSingleton = iActivityManagerSingletonField.get(null);
        // 3、获得AMS的代理对象
        Class<?> singleton = Class.forName("android.util.Singleton"); // 是一个 android.util.Singleton对象;
        Field mInstanceField = singleton.getDeclaredField("mInstance"); // 取出mInstance字段
        mInstanceField.setAccessible(true);
        Object activityManager = mInstanceField.get(activityManagerSingleton);
        // 4、创建AMS的代理对象的【代理对象】
        Class<?> iActivityManagerInterface = Class.forName("android.app.IActivityManager");
        Object proxyActivityManager = Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] { iActivityManagerInterface }, new IActivityManagerInvocationHandler(activityManager));
        // 5、将IActivityManagerSingleton的“mInstace字段”替换为"ActivityManager的代理对象"，进行我们需要的将插件Actiivty替换为坑位Activity的操作
        mInstanceField.set(activityManagerSingleton, proxyActivityManager);
    }

    public static final String EXTRA_TARGET_INTENT = "extra_target_intent";

    /**=======================================================
     * @功能: 用于拦截ActivityManager的startActivity操作
     *========================================================*/
    public class IActivityManagerInvocationHandler implements InvocationHandler {
        private static final String TAG = "feather";

        Object mActivityManager;
        public IActivityManagerInvocationHandler(Object activityManager) {
            mActivityManager = activityManager;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

            // 1、startActivity以外的操作直接透传给“原ActivityManager”处理
            if (!"startActivity".equals(method.getName())) {
                return method.invoke(mActivityManager, args);
            }else{
                // 2、拦截startActivity方法
                Log.e(TAG,"startActivity方法拦截了");

                // 3、找到参数里面的第一个Intent对象。
                Intent intent = null;
                for (Object arg : args) {
                    if (arg instanceof Intent) {
                        intent = (Intent) arg;

                        Log.e(TAG,"原Intent = " + intent);
                        /**=======================================
                         * 4、获取到原Activity的信息，并以key-value形式保存到Intent的extra中
                         *===================================*/
                        // 1. 获取到原componentName(原Activity信息)
                        ComponentName rawComponentName = intent.getComponent();
                        // 2. 保存到Extra中
                        intent.putExtra(EXTRA_TARGET_INTENT, rawComponentName); // "extra_target_intent"
                        /**=======================================
                         * 5、将目标Activity替换为占坑Activity
                         *===================================*/
                        // 1. 宿主apk的"包名"
                        String stubPackage = getPackageName();
                        // 2. 以宿主包名 + 占坑Activity的信息构建ComponentName
                        ComponentName componentName = new ComponentName(stubPackage, PitActivity.class.getName());
                        // 3. 替换Component
                        intent.setComponent(componentName);

                        Log.e(TAG,"新Intent = " + intent);
                        break;
                    }
                }
                Log.e(TAG,"startActivity方法 hook 成功");
                // 4、以修改后的intent参数去startActivity
                return method.invoke(mActivityManager, args);
            }
        }
    }

    /**======================================================
     * @function 通过系统验证后，将占坑Activity换回目标Activity
     *  1. 给ActivityThead的Handler H设置一个回调接口Callback
     *  2. 在Handler调用handleMessage()前被我们的Callback进行拦截处理
     *======================================================*/
    public static void hookActivityThreadHandlerH() throws Exception {

        // 1、获取到ActivityThread类
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        // 2、调用“currentActivityThread()”获取到ActivityThread对象
        Method currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread");
        currentActivityThreadMethod.setAccessible(true);
        Object activityThread = currentActivityThreadMethod.invoke(null);
        // 3、获取到ActivityThread对象的字段mH(Handler H)
        Field mHField = activityThreadClass.getDeclaredField("mH");
        mHField.setAccessible(true);
        Handler mH = (Handler) mHField.get(activityThread);
        /**==========================================================
         * 4、根据消息机制，给mH设置Callback，该Callback优先于handlerMessage()执行。在activity启动的流程中，替换Intent
         *   1. Handler具有成员变量mCallback
         *   2. 更改mH的mCallback, 为我们自定义的Handler Callback
         *============================================================*/
        Field mCallbackField = Handler.class.getDeclaredField("mCallback");
        mCallbackField.setAccessible(true);
        mCallbackField.set(mH, new ActivityThreadHandlerHCallback(mH));
    }

    /**=======================================
     * @功能: 自定义Callback，重点处理Activity
     *  1. 将系统返回的Intent中的ComponentName替换为原来的内容
     *========================================*/
    public static class ActivityThreadHandlerHCallback implements Handler.Callback {
        // 和源码一致，按道理需要反射获得
        private static final int EXECUTE_TRANSACTION = 159;

        Handler mHandlerH;
        public ActivityThreadHandlerHCallback(Handler rawHandler){
            mHandlerH = rawHandler;
        }
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what){
                case EXECUTE_TRANSACTION:
                    try {
                        // 1、msg.obj需要是ClientTransaction的对象
                        Class<?> clientTransactionClass = Class.forName("android.app.servertransaction.ClientTransaction");
                        if(clientTransactionClass.isInstance(msg.obj)){
                            // 2、从ClientTransaction对象中取出: List<ClientTransactionItem> mActivityCallbacks
                            Field mActivityCallbacksField = clientTransactionClass.getDeclaredField("mActivityCallbacks");
                            mActivityCallbacksField.setAccessible(true);
                            List mActivityCallbacks = (List) mActivityCallbacksField.get(msg.obj);

                            // 3、遍历Callbacks列表，取出LaunchActivityItem。
                            for (Object callback : mActivityCallbacks) {
                                // 4、如果Callback是LaunchActivityItem类型的，表明是startActivity的后续处理
                                Class<?> launchActivityItemClass = Class.forName("android.app.servertransaction.LaunchActivityItem");
                                if(launchActivityItemClass.isInstance(callback)){
                                    // 5、获取到Intent
                                    Field mIntentField = launchActivityItemClass.getDeclaredField("mIntent");
                                    mIntentField.setAccessible(true);
                                    Intent mIntent = (Intent) mIntentField.get(callback);
                                    // 6、获取到之前缓存的ComponentName，并放回Intent
                                    ComponentName componentName = mIntent.getParcelableExtra(EXTRA_TARGET_INTENT);
                                    if(componentName != null){
                                        mIntent.setComponent(componentName);
                                    }
                                    // 7、将LaunchActivityItem的成员变量mIntent进行替换
                                    mIntentField.set(callback, mIntent);
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
            }
            // 8、交给ActivityThread里面的“Handler H”进行处理。
            mHandlerH.handleMessage(msg);
            // 9、不再需要后续的处理，避免再去执行Handler的handleMessage(导致重复处理)
            return true;
        }
    }

    /**========================================================================
     * @function 将“assets/external”下的外置插件复制到宿主app的数据目录中，模拟外置插件的下载
     * @param assetFileName assets目录中的文件路径名，例如"assets/external/webview.apk"中的"external/webview.apk"
     * @param newFileName 插件名如"webview.apk"
     *========================================================================*/
    private void copyAssetsFileToAppFiles(String assetFileName, String newFileName){

        // 1、assets/external中的外置插件apk作为输入
        try(InputStream inputStream = this.getAssets().open(assetFileName);
            // 2、宿主appp数据目录作为输出
            FileOutputStream fileOutputStream = this.openFileOutput(newFileName, MODE_PRIVATE)) {

            // 3、IO进行文件的复制
            int byteCount = 0;
            byte[] buffer = new byte[1024];
            while((byteCount = inputStream.read(buffer)) != -1){
                fileOutputStream.write(buffer, 0, byteCount);
            }
            // 4、刷新输出流
            fileOutputStream.flush();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    AssetManager mPluginAssetManager; // 创建属于插件的AssetManager
    Resources mPluginResources; // 创建属于插件的Resources
    Resources.Theme mPluginTheme; // 创建属于插件的Theme
    /**============================================
     * @功能: 创建属于插件的AssetManager和Resources
     *  1. 通过addAssetPath将插件资源包添加到AssetManager中
     *  2. 通过创建的插件AssetManager来创建插件Resources
     *============================================*/
    private void preparePluginResources(String pluginApkName) throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        String pluginFilePath = getFilesDir().getAbsolutePath() + File.separator + pluginApkName;
        File pluginFile = new File(pluginFilePath);
        if(!pluginFile.exists()){
            Log.e("feather", "插件Apk不存在！");
        }
        // 1、创建新的AssetManager
        mPluginAssetManager = AssetManager.class.newInstance();
        // 2、调用AssetManager的addAssetPath将插件的路径添加进去
        Method addAssetPathMethod = mPluginAssetManager.getClass().getDeclaredMethod("addAssetPath", String.class);
        addAssetPathMethod.setAccessible(true);
        addAssetPathMethod.invoke(mPluginAssetManager, pluginFilePath);
        // 3、创建属于插件的Resources
        Resources hostResources = getResources();
        mPluginResources = new Resources(mPluginAssetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
        // 4、创建属于插件的Theme
        mPluginTheme = mPluginResources.newTheme();
        mPluginTheme.setTo(super.getTheme());
    }
    @Override
    public Resources getResources() {
        return mPluginResources == null ? super.getResources() : mPluginResources;
    }
    @Override
    public AssetManager getAssets() {
        return mPluginAssetManager == null ? super.getAssets() : mPluginAssetManager;
    }
    @Override
    public Resources.Theme getTheme() {
        return mPluginTheme == null ? super.getTheme() : mPluginTheme;
    }
}
