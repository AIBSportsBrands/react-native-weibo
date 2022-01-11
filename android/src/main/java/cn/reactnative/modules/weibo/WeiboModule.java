package cn.reactnative.modules.weibo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.net.Uri;
import android.util.Log;

import com.facebook.common.executors.UiThreadImmediateExecutorService;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.UriUtil;
import com.facebook.datasource.BaseDataSubscriber;
import com.facebook.datasource.DataSource;
import com.facebook.datasource.DataSubscriber;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.drawee.drawable.OrientedDrawable;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.image.CloseableStaticBitmap;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import com.sina.weibo.sdk.api.ImageObject;
import com.sina.weibo.sdk.api.TextObject;
import com.sina.weibo.sdk.api.WebpageObject;
import com.sina.weibo.sdk.api.WeiboMultiMessage;
import com.sina.weibo.sdk.share.WbShareCallback;
import com.sina.weibo.sdk.auth.AuthInfo;
import com.sina.weibo.sdk.auth.WbAuthListener;
import com.sina.weibo.sdk.auth.Oauth2AccessToken;
import com.sina.weibo.sdk.openapi.IWBAPI;
import com.sina.weibo.sdk.openapi.WBAPIFactory;
import com.sina.weibo.sdk.openapi.SdkListener;
import com.sina.weibo.sdk.common.UiError;


import java.util.Date;
import javax.annotation.Nullable;


public class WeiboModule extends ReactContextBaseJavaModule implements ActivityEventListener, WbShareCallback  {

    public WeiboModule(ReactApplicationContext reactContext) {
        super(reactContext);
        ApplicationInfo appInfo = null;
        try {
            appInfo = reactContext.getPackageManager().getApplicationInfo(reactContext.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Error(e);
        }
        if (!appInfo.metaData.containsKey("WB_APPID")){
            throw new Error("meta-data WB_APPID not found in AndroidManifest.xml");
        }
        this.appId = appInfo.metaData.get("WB_APPID").toString().substring(2);
    }

    private static final String RCTWBEventName = "Weibo_Resp";

    private IWBAPI mWBAPI;
    private String appId;
    private boolean wbSdkInstalled = false;


    private static final String RCTWBShareTypeLink = "link";
    private static final String RCTWBShareTypeImage = "image";
    private static final String RCTWBShareTypeText = "text";
    private static final String RCTWBShareTypeVideo = "video";

    private static final String RCTWBShareType = "type";
    private static final String RCTWBShareText = "text";
    private static final String RCTWBShareTitle = "title";
    private static final String RCTWBShareDescription = "description";
    private static final String RCTWBShareWebpageUrl = "webpageUrl";
    private static final String RCTWBSharePropVideoPath = "videoPath";
    private static final String RCTWBShareImageUrl = "imageUrl";
    private static final String RCTWBShareAccessToken = "accessToken";

    private static WeiboModule gModule = null;

    @Override
    public void initialize() {
        super.initialize();
        gModule = this;
        getReactApplicationContext().addActivityEventListener(this);
        WritableMap config = Arguments.createMap();
        config.putString("redirectURI", "https://api.weibo.com/oauth2/default.html");
        config.putString("scope", "all");
        _installWbSdk(config);
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        gModule = null;
        getReactApplicationContext().removeActivityEventListener(this);
    }

    @Override
    public String getName() {
        return "RCTWeiboAPI";
    }



    private void _installWbSdk(final ReadableMap config) {
        if (!wbSdkInstalled) {
            AuthInfo sinaAuthInfo = this._genAuthInfo(config);
            mWBAPI = WBAPIFactory.createWBAPI(getReactApplicationContext());
            mWBAPI.registerApp(getReactApplicationContext(), sinaAuthInfo, new SdkListener() {
                @Override
                public void onInitSuccess() {
                    wbSdkInstalled = true;
                    Log.d(">>>weibo", "_installWbSdk onInitSuccess");
                }
                @Override
                public void onInitFailure(Exception e) { // SDK初始化失败回调
                    Log.d(">>>weibo", "_installWbSdk onInitFailure");
                } 
            });
            
        }
    }

    @ReactMethod
    public void login(final ReadableMap config, final Callback callback){
        this._installWbSdk(config);
        mWBAPI.authorize(this.getCurrentActivity(), new WbAuthListener() {
            @Override
            public void onComplete(Oauth2AccessToken token) {
                WritableMap event = Arguments.createMap();
                if (token.isSessionValid()) {
                    event.putString("accessToken", token.getAccessToken());
                    event.putDouble("expirationDate", token.getExpiresTime());
                    event.putString("userID", token.getUid());
                    event.putString("refreshToken", token.getRefreshToken());
                    event.putInt("errCode", 0);
                } else {
                    event.putInt("errCode", -1);
                    event.putString("errMsg", "token invalid");
                }
                event.putString("type", "WBAuthorizeResponse");
                getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(RCTWBEventName, event);
            }

            @Override
            public void onError(UiError error) {
                WritableMap event = Arguments.createMap();
                event.putString("type", "WBAuthorizeResponse");
                event.putString("errMsg", error.errorMessage);
                event.putInt("errCode", -1);
                getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(RCTWBEventName, event);
            }
            @Override
            public void onCancel() {
                WritableMap event = Arguments.createMap();
                event.putString("type", "WBAuthorizeResponse");
                event.putString("errMsg", "Cancel");
                event.putInt("errCode", -1);
                getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(RCTWBEventName, event);
            }
        });
    }

    @ReactMethod
    public void shareToWeibo(final ReadableMap data, Callback callback){
        this._installWbSdk(data);
        if (data.hasKey(RCTWBShareImageUrl)) {
            String imageUrl = data.getString(RCTWBShareImageUrl);
            if (imageUrl == null || imageUrl.isEmpty()) {
                this._share(data, null);
            } else {
                DataSubscriber<CloseableReference<CloseableImage>> dataSubscriber =
                        new BaseDataSubscriber<CloseableReference<CloseableImage>>() {
                            @Override
                            public void onNewResultImpl(DataSource<CloseableReference<CloseableImage>> dataSource) {
                                // isFinished must be obtained before image, otherwise we might set intermediate result
                                // as final image.
                                boolean isFinished = dataSource.isFinished();
                                CloseableReference<CloseableImage> image = dataSource.getResult();
                                if (image != null) {
                                    Drawable drawable = _createDrawable(image);
                                    Bitmap bitmap = _drawable2Bitmap(drawable);
                                    _share(data, bitmap);
                                } else if (isFinished) {
                                    _share(data, null);
                                }
                                dataSource.close();
                            }

                            @Override
                            public void onFailureImpl(DataSource<CloseableReference<CloseableImage>> dataSource) {
                                dataSource.close();
                                _share(data, null);
                            }

                            @Override
                            public void onProgressUpdate(DataSource<CloseableReference<CloseableImage>> dataSource) {
                            }
                        };
                ResizeOptions resizeOptions = null;
                if (!data.hasKey(RCTWBShareType) || !data.getString(RCTWBShareType).equals(RCTWBShareTypeImage)) {
                    resizeOptions = new ResizeOptions(80, 80);
                }

                this._downloadImage(imageUrl, resizeOptions, dataSubscriber);
            }
        }
        else {
            this._share(data, null);
        }

        callback.invoke();
    }

    @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, @Nullable Intent data){
        mWBAPI.authorizeCallback(activity, requestCode, resultCode, data);
    }

    public void onNewIntent(Intent intent){

    }



    private void _share(ReadableMap data, Bitmap bitmap) {

        WeiboMultiMessage weiboMessage = new WeiboMultiMessage();//初始化微博的分享消息
        TextObject textObject = new TextObject();
        if (data.hasKey(RCTWBShareText)) {
            textObject.text = data.getString(RCTWBShareText);
        }
        weiboMessage.textObject = textObject;

        String type = RCTWBShareTypeLink;
        if (data.hasKey(RCTWBShareType)){
            type = data.getString(RCTWBShareType);
        }

        if (type.equals(RCTWBShareTypeText)) {
        }
        else if (type.equals(RCTWBShareTypeImage)) {
            ImageObject imageObject = new ImageObject();
            if (bitmap != null) {
                imageObject.setImageData(bitmap);
            }
            weiboMessage.imageObject = imageObject;
        }
        else {
            if (type.equals(RCTWBShareTypeLink)) {
                WebpageObject webpageObject = new WebpageObject();
                if (data.hasKey(RCTWBShareWebpageUrl)) {
                    webpageObject.actionUrl = data.getString(RCTWBShareWebpageUrl);
                }
                weiboMessage.mediaObject = webpageObject;
            }
            else if (type.equals(RCTWBShareTypeVideo)) {
                //todo share video
            }
            if (data.hasKey(RCTWBShareDescription)) {
                weiboMessage.mediaObject.description = data.getString(RCTWBShareDescription);
            }
            if (data.hasKey(RCTWBShareTitle)) {
                weiboMessage.mediaObject.title = data.getString(RCTWBShareTitle);
            }
            if (bitmap != null) {
//                weiboMessage.mediaObject.setThumbImage(bitmap);
            }
            weiboMessage.mediaObject.identify = new Date().toString();
        }
        mWBAPI.shareMessage(this.getCurrentActivity(), weiboMessage, false);
    }
    @Override
    public void onComplete() {
        WritableMap event = Arguments.createMap();
        event.putString("type", "WBSendMessageToWeiboResponse");
        event.putInt("errCode", 0);

        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(RCTWBEventName, event);
    }

    @Override
    public void onError(UiError error) {
        WritableMap map = Arguments.createMap();
        map.putInt("errCode", -1);
        map.putString("errMsg", "分享失败");
        map.putString("type", "WBSendMessageToWeiboResponse");
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(RCTWBEventName, map);
    }

    @Override
    public void onCancel() {
        WritableMap map = Arguments.createMap();
        map.putInt("errCode", -1);
        map.putString("errMsg", "分享取消");
        map.putString("type", "WBSendMessageToWeiboResponse");
        getReactApplicationContext()
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(RCTWBEventName, map);
    }


    private AuthInfo _genAuthInfo(ReadableMap config) {
        String redirectURI = "";
        if (config.hasKey("redirectURI")) {
            redirectURI = config.getString("redirectURI");
        }

        String scope = "";
        if (config.hasKey("scope")) {
            scope = config.getString("scope");
        }
        final AuthInfo sinaAuthInfo = new AuthInfo(getReactApplicationContext(), this.appId, redirectURI, scope);
        return sinaAuthInfo;
    }

    private void  _downloadImage(String imageUrl, ResizeOptions resizeOptions,DataSubscriber<CloseableReference<CloseableImage>> dataSubscriber) {
        Uri uri = null;
        try {
            uri = Uri.parse(imageUrl);
            // Verify scheme is set, so that relative uri (used by static resources) are not handled.
            if (uri.getScheme() == null) {
                uri = null;
            }
        } catch (Exception e) {
            // ignore malformed uri, then attempt to extract resource ID.
        }
        if (uri == null) {
            uri = _getResourceDrawableUri(getReactApplicationContext(), imageUrl);
        } else {
        }

        ImageRequestBuilder builder = ImageRequestBuilder.newBuilderWithSource(uri);
        if (resizeOptions != null) {
            builder.setResizeOptions(resizeOptions);
        }
        ImageRequest imageRequest = builder.build();

        ImagePipeline imagePipeline = Fresco.getImagePipeline();
        DataSource<CloseableReference<CloseableImage>> dataSource = imagePipeline.fetchDecodedImage(imageRequest, null);
        dataSource.subscribe(dataSubscriber, UiThreadImmediateExecutorService.getInstance());
    }

    private static @Nullable
    Uri _getResourceDrawableUri(Context context, @Nullable String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        name = name.toLowerCase().replace("-", "_");
        int resId = context.getResources().getIdentifier(
                name,
                "drawable",
                context.getPackageName());
        return new Uri.Builder()
                .scheme(UriUtil.LOCAL_RESOURCE_SCHEME)
                .path(String.valueOf(resId))
                .build();
    }

    private Drawable _createDrawable(CloseableReference<CloseableImage> image) {
        Preconditions.checkState(CloseableReference.isValid(image));
        CloseableImage closeableImage = image.get();
        if (closeableImage instanceof CloseableStaticBitmap) {
            CloseableStaticBitmap closeableStaticBitmap = (CloseableStaticBitmap) closeableImage;
            BitmapDrawable bitmapDrawable = new BitmapDrawable(
                    getReactApplicationContext().getResources(),
                    closeableStaticBitmap.getUnderlyingBitmap());
            if (closeableStaticBitmap.getRotationAngle() == 0 ||
                    closeableStaticBitmap.getRotationAngle() == EncodedImage.UNKNOWN_ROTATION_ANGLE) {
                return bitmapDrawable;
            } else {
                return new OrientedDrawable(bitmapDrawable, closeableStaticBitmap.getRotationAngle());
            }
        } else {
            throw new UnsupportedOperationException("Unrecognized image class: " + closeableImage);
        }
    }

    private Bitmap _drawable2Bitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        } else if (drawable instanceof NinePatchDrawable) {
            Bitmap bitmap = Bitmap
                    .createBitmap(
                            drawable.getIntrinsicWidth(),
                            drawable.getIntrinsicHeight(),
                            drawable.getOpacity() != PixelFormat.OPAQUE ? Bitmap.Config.ARGB_8888
                                    : Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight());
            drawable.draw(canvas);
            return bitmap;
        } else {
            return null;
        }
    }
}
