package com.sarathi.geckotest;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import java.util.List;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Reject cached accessibility requests after this surface changes ownership. */
final class ApplicantGeckoView extends GeckoView {
 private boolean uiVisible=true;
 private long accessibilityEpoch;
 private AccessibilityNodeProvider rawProvider,guardedProvider;
 ApplicantGeckoView(Context context){super(context);}
 void setUiVisible(boolean value){
  if(uiVisible==value)return;
  uiVisible=value;accessibilityEpoch++;rawProvider=null;guardedProvider=null;
 }
 void invalidateAccessibilityProvider(){
  accessibilityEpoch++;rawProvider=null;guardedProvider=null;
 }
 void clearPlatformAccessibilityFocus(){
  // Only call after releasing the session. Visibility clears Android's host focus
  // without sending an ACTION_CLEAR_ACCESSIBILITY_FOCUS into a departing document.
  if(getSession()!=null)throw new IllegalStateException("Detach session before clearing host focus");
  int visibility=getVisibility();
  if(visibility!=View.INVISIBLE){setVisibility(View.INVISIBLE);setVisibility(visibility);}
 }
 private boolean ownsVisibleSession(GeckoSession session,long epoch){
  return epoch==accessibilityEpoch&&uiVisible&&isShown()&&getWindowVisibility()==View.VISIBLE&&
    session!=null&&session==getSession()&&session.isOpen()&&session.getAccessibility().getView()==this;
 }
 @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){
  final GeckoSession session=getSession();final long epoch=accessibilityEpoch;
  if(!ownsVisibleSession(session,epoch))return null;
  final AccessibilityNodeProvider delegate=super.getAccessibilityNodeProvider();
  if(delegate==null)return null;
  if(rawProvider==delegate&&guardedProvider!=null)return guardedProvider;
  rawProvider=delegate;
  guardedProvider=new AccessibilityNodeProvider(){
   private boolean valid(){return ownsVisibleSession(session,epoch);}
   @Override public AccessibilityNodeInfo createAccessibilityNodeInfo(int id){return valid()?delegate.createAccessibilityNodeInfo(id):null;}
   @Override public AccessibilityNodeInfo findFocus(int focus){return valid()?delegate.findFocus(focus):null;}
   @Override public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(String text,int id){return valid()?delegate.findAccessibilityNodeInfosByText(text,id):java.util.Collections.emptyList();}
   @Override public boolean performAction(int id,int action,Bundle arguments){return valid()&&delegate.performAction(id,action,arguments);}
   @Override public void addExtraDataToAccessibilityNodeInfo(int id,AccessibilityNodeInfo info,String key,Bundle arguments){if(valid())delegate.addExtraDataToAccessibilityNodeInfo(id,info,key,arguments);}
  };
  return guardedProvider;
 }
}
