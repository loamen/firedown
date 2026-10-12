package com.solarized.firedown.autocomplete;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.solarized.firedown.geckoview.GeckoToolbar;


public class AutoCompleteViewBehavior extends CoordinatorLayout.Behavior<View> {

    private final AutoCompleteView mAutoCompleteView;

    public AutoCompleteViewBehavior(@Nullable Context context, @Nullable AttributeSet attrs, @NonNull final View parentView) {
        super(context, attrs);
        mAutoCompleteView = recursivelyFindAutoCompleteView(parentView);
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency) {

        if(dependency instanceof GeckoToolbar){
            return true;
        }

        return super.layoutDependsOn(parent, child, dependency);
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent, @NonNull View child, @NonNull View dependency){
        if(mAutoCompleteView != null){
            // The toolbar's height is read LIVE, not frozen at construction:
            // with the edge-to-edge browser the toolbar self-pads by the
            // status inset AFTER this behavior is built, so a constructor
            // snapshot would park the suggestions panel under the toolbar.
            //
            // Positioned off the toolbar's laid-out BOTTOM, not its height
            // alone: on the home the toolbar is laid out at a top margin (the
            // status inset) while the overlay is laid out at 0, so the
            // overlay must start at the toolbar's bottom edge, wherever that
            // is. In the browser both are laid out at the root's padding
            // top, so the two tops cancel and this reduces to the old
            // translation + height.
            float toolbarBottom = dependency.getTop() + dependency.getTranslationY()
                    + dependency.getHeight();
            mAutoCompleteView.setTranslationY(toolbarBottom - child.getTop());
        }
        return true;
    }


    @Nullable
    private static AutoCompleteView recursivelyFindAutoCompleteView(View view) {
        if (view instanceof ViewGroup viewGroup) {
            //ViewGroup

            if (!(viewGroup instanceof AutoCompleteView)) {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    AutoCompleteView result = recursivelyFindAutoCompleteView(viewGroup.getChildAt(i));

                    if (result != null) {
                        return result;
                    }
                }
            }else{

                return (AutoCompleteView) viewGroup;
            }
        }
        return null;
    }




}
