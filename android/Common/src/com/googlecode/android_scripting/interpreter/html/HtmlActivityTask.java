// Copyright 2010 Google Inc. All Rights Reserved.

package com.googlecode.android_scripting.interpreter.html;

import android.R;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.View;
import android.view.Window;
import android.view.ContextMenu.ContextMenuInfo;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.googlecode.android_scripting.Log;
import com.googlecode.android_scripting.SingleThreadExecutor;
import com.googlecode.android_scripting.facade.EventFacade;
import com.googlecode.android_scripting.facade.ui.UiFacade;
import com.googlecode.android_scripting.future.FutureActivityTask;
import com.googlecode.android_scripting.jsonrpc.JsonRpcResult;
import com.googlecode.android_scripting.jsonrpc.RpcReceiverManager;
import com.googlecode.android_scripting.rpc.MethodDescriptor;
import com.googlecode.android_scripting.rpc.RpcError;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

public class HtmlActivityTask extends FutureActivityTask<Void> {

  private static final String PREFIX = "file://";

  private static final String ANDROID_JS =
      "javascript:function Android(){ this.id = 0, "
          + "this.call = function(){"
          + "this.id += 1;"
          + "var method = arguments[0]; var args = [];for (var i=1; i<arguments.length; i++){args[i-1]=arguments[i];}"
          + "var request = JSON.stringify({'id': this.id, 'method': method,'params': args});"
          + "var response = droid_rpc.call(request);" + "return eval(\"(\" + response + \")\");"
          + "}}";

  private final RpcReceiverManager mReceiverManager;
  private final String mJsonSource;
  private final String mSource;
  private final JavaScriptWrapper mWrapper;
  private final HtmlEventObserver mObserver;
  private ChromeClient mChromeClient;
  private WebView mView;

  public HtmlActivityTask(RpcReceiverManager manager, String jsonSource, String file) {
    mReceiverManager = manager;
    mJsonSource = jsonSource;
    mSource = PREFIX + file;
    mWrapper = new JavaScriptWrapper();
    mObserver = new HtmlEventObserver();
    mReceiverManager.getReceiver(EventFacade.class).addEventObserver(mObserver);
  }

  @Override
  public void onCreate() {
    getActivity().setTheme(R.style.Theme);
    mView = new WebView(getActivity());
    mView.setId(1);
    mView.getSettings().setJavaScriptEnabled(true);
    mView.addJavascriptInterface(mWrapper, "droid_rpc");
    mView.addJavascriptInterface(new Object() {
      @SuppressWarnings("unused")
      public void registerEventCallback(String event, String jsFunction) {
        mObserver.registerEventCallback(event, jsFunction);
      }
    }, "droid_callback");
    getActivity().setContentView(mView);
    mView.setOnCreateContextMenuListener(getActivity());
    mChromeClient = new ChromeClient(getActivity());
    mView.setWebChromeClient(mChromeClient);
    mView.loadUrl("javascript:" + mJsonSource);
    mView.loadUrl(ANDROID_JS);
    mView.loadUrl(mSource);
  }

  @Override
  public void onDestroy() {
    mReceiverManager.getReceiver(EventFacade.class).removeEventObserver(mObserver);
    mView.destroy();
    mView = null;
  }

  @Override
  public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    return true;
  }

  private class JavaScriptWrapper {
    @SuppressWarnings("unused")
    public String call(String data) throws JSONException {
      Log.v("Received: " + data);
      JSONObject request = new JSONObject(data);
      int id = request.getInt("id");
      String method = request.getString("method");
      JSONArray params = request.getJSONArray("params");
      MethodDescriptor rpc = mReceiverManager.getMethodDescriptor(method);
      if (rpc == null) {
        return JsonRpcResult.error(id, new RpcError("Unknown RPC.")).toString();
      }
      try {
        return JsonRpcResult.result(id, rpc.invoke(mReceiverManager, params)).toString();
      } catch (Throwable t) {
        Log.e("Invocation error.", t);
        return JsonRpcResult.error(id, t).toString();
      }
    }
  }

  private class HtmlEventObserver implements EventFacade.EventObserver {
    private Map<String, String> mEventMap = new HashMap<String, String>();

    public void registerEventCallback(String eventName, String jsFunction) {
      mEventMap.put(eventName, jsFunction);
    }

    @Override
    public void onEventReceived(String eventName, Object data) {
      if (mEventMap.containsKey(eventName)) {
        mView.loadUrl("javascript:" + mEventMap.get(eventName));
      }
    }

  }

  private class ChromeClient extends WebChromeClient {
    private final static String JS_TITLE = "javaScript dialog";

    private final Activity mActivity;
    private final Resources mResources;
    private final ExecutorService mmExecutor;

    public ChromeClient(Activity activity) {
      mActivity = activity;
      mResources = mActivity.getResources();
      mmExecutor = new SingleThreadExecutor();
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {
      mActivity.setTitle(title);
    }

    @Override
    public void onReceivedIcon(WebView view, Bitmap icon) {
      mActivity.getWindow().requestFeature(Window.FEATURE_RIGHT_ICON);
      mActivity.getWindow().setFeatureDrawable(Window.FEATURE_RIGHT_ICON, new BitmapDrawable(icon));
    }

    @Override
    public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
      final UiFacade uiFacade = mReceiverManager.getReceiver(UiFacade.class);
      uiFacade.dialogCreateAlert(JS_TITLE, message);
      uiFacade.dialogSetPositiveButtonText(mResources.getString(android.R.string.ok));
      uiFacade.dialogSetCancellable(false);

      mmExecutor.execute(new Runnable() {

        @Override
        public void run() {
          try {
            uiFacade.dialogShow();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          uiFacade.dialogGetResponse();
          result.confirm();
        }
      });
      return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
      final UiFacade uiFacade = mReceiverManager.getReceiver(UiFacade.class);
      uiFacade.dialogCreateAlert(JS_TITLE, message);
      uiFacade.dialogSetPositiveButtonText(mResources.getString(android.R.string.ok));
      uiFacade.dialogSetNegativeButtonText(mResources.getString(android.R.string.cancel));

      mmExecutor.execute(new Runnable() {

        @Override
        public void run() {
          try {
            uiFacade.dialogShow();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          Map<String, Object> mResultMap = (Map<String, Object>) uiFacade.dialogGetResponse();

          if (mResultMap.containsKey("which") && mResultMap.get("which").equals("positive")) {
            result.confirm();
          } else {
            result.cancel();
          }
        }
      });

      return true;
    }

    @Override
    public boolean onJsPrompt(WebView view, String url, final String message,
        final String defaultValue, final JsPromptResult result) {
      final UiFacade uiFacade = mReceiverManager.getReceiver(UiFacade.class);

      mmExecutor.execute(new Runnable() {

        @Override
        public void run() {
          String value = null;
          try {
            value = uiFacade.dialogGetInput(JS_TITLE, message, defaultValue);
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
          if (value != null) {
            result.confirm(value);
          } else {
            result.cancel();
          }
        }
      });

      return true;
    }

  }
}
