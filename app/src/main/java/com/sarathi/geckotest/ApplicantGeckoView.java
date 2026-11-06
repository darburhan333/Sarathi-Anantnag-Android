package com.sarathi.geckotest;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

/** Expose accessibility only for a visible surface that still owns its live session. */
final class ApplicantGeckoView extends GeckoView {
 private boolean uiVisible=true,clearingPlatformFocus;
 ApplicantGeckoView(Context context){super(context);}
 void setUiVisible(boolean value){
  if(uiVisible==value)return;
  uiVisible=value;if(!value)clearPlatformAccessibilityFocus();
 }
 void clearPlatformAccessibilityFocus(){
  // ViewRoot can retain a virtual-node focus after the document or Activity changes.
  // Clear that Android focus without synchronously calling the old native provider.
  clearingPlatformFocus=true;
  try{performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS,null);}finally{clearingPlatformFocus=false;}
 }
 @Override public AccessibilityNodeProvider getAccessibilityNodeProvider(){
  GeckoSession session=getSession();
  if(clearingPlatformFocus||!uiVisible||!isShown()||getWindowVisibility()!=View.VISIBLE||
     session==null||!session.isOpen()||session.getAccessibility().getView()!=this)return null;
  return super.getAccessibilityNodeProvider();
 }
}
