/**
 * @file WBListView.java
 * @package com.tencent.weibu.view
 * @create Mar 18, 2012 6:28:41 PM
 * @author maxiezhang@tencent.com
 * @description 添加了滚动触边时的回滚效果，暂时仅支持垂直滚动。
 */
package me.maxwin.view;

import me.maxwin.R;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;
import android.view.animation.DecelerateInterpolator;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Scroller;

public class XListView extends ListView implements OnScrollListener {

	private final static String TAG = "XListView";

	private float mLastY = -1; // save event y
	private Scroller mScroller; // used for scroll back
	private OnScrollListener mScrollListener; // user's scroll listener

	// -- pull down to refresh
	private View mHeaderView;
	private View mHeaderContentView;

	// current header height
	private int mHeaderHeight;
	private boolean mEnablePullRefresh = true;
	private boolean mPullRefreshing = false; // is refreshing?
	// refresh/load listener
	private IXListViewListener mListViewListener;

	// -- pull up to load
	private View mFooterView;
	private View mFooterContentView;
	private boolean mEnablePullLoad = false;
	private boolean mPullLoading = false;

	// total items, use to test whether the footer view is showing.
	private int mTotalItemCount;

	// mScroller will reset header or footer, use this status to identify.
	private int mScrollBackStatus;
	private final static int SCROLLBACK_HEADER = 0;
	private final static int SCROLLBACK_FOOTER = 1;

	// duration for scroll back.
	private final static int SCROLL_DURATION = 400; // ms
	private final static int PULL_LOAD_MORE_DELTA = 50; // trigger for load more
	private final static float OFFSET_RADIO = 1.8f; // iOS like

	/**
	 * @param context
	 */
	public XListView(Context context) {
		super(context);
		initWithContext(context);
	}

	public XListView(Context context, AttributeSet attrs) {
		super(context, attrs);
		initWithContext(context);
	}

	public XListView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		initWithContext(context);
	}

	private void initWithContext(Context context) {
		mScroller = new Scroller(context, new DecelerateInterpolator());
		// XListView need the scroll info. It will dispatch the message to
		// user's listener as well.
		super.setOnScrollListener(this);

		// init header view.
		mHeaderView = LayoutInflater.from(context).inflate(
				R.layout.xlistview_header, null);
		mHeaderContentView = mHeaderView.findViewById(R.id.xlistview_header_content);
		addHeaderView(mHeaderView);

		// init footer view.
		mFooterView = LayoutInflater.from(context).inflate(
				R.layout.xlistview_footer, null);

		// init header view's height
		mHeaderView.getViewTreeObserver().addOnGlobalLayoutListener(
				new OnGlobalLayoutListener() {
					@Override
					public void onGlobalLayout() {
						mHeaderHeight = mHeaderContentView.getHeight();
						getViewTreeObserver()
								.removeGlobalOnLayoutListener(this);
					}
				});
	}

	@Override
	public void setAdapter(ListAdapter adapter) {
		// add load more view just before user call setAdapter.
		addFooterView(mFooterView);
		super.setAdapter(adapter);
	}

	/**
	 * enable/disable pull refresh. 
	 * @param enable
	 */
	public void setPullRefreshEnable(boolean enable) {
 		mEnablePullRefresh = enable;
		if (!mEnablePullRefresh) { // disable
			mHeaderContentView.setVisibility(View.INVISIBLE);
		} else {
			mHeaderContentView.setVisibility(View.VISIBLE);
		}
	}

	/**
	 * 设置启用上拉载入更多
	 * 
	 * @param enable
	 */
	public void setPullLoadEnable(boolean enable) {
		mEnablePullLoad = enable;
		if (!mEnablePullLoad) {
			mPullLoadViewContent.setVisibility(View.INVISIBLE);
			mPullLoadView.setOnClickListener(null);
		} else {
			mPullLoading = false;
			mPullLoadViewContent.setVisibility(View.VISIBLE);
			mPullLoadView.setState(LoadMoreView.STATE_NORMAL);
			mPullLoadView.setOnClickListener(new OnClickListener() {
				@Override
				public void onClick(View v) {
					startLoadMore();
				}
			});
		}
	}

	/**
	 * 停止下拉刷新，回推下拉控件
	 */
	public void stopRefresh() {
		if (mPullRefreshing == true) {
			mPullRefreshing = false;
			resetHeaderHeight();
		}
	}

	/**
	 * 停止上拉载入更多
	 */
	public void stopLoadMore() {
		if (mPullLoading == true) {
			mPullLoading = false;
			mPullLoadView.setState(LoadMoreView.STATE_NORMAL);
		}
	}

	/**
	 * 触发滚动回调，统一入口，会检查mScrollListener合法性
	 */
	private void invokeOnScrolling() {
		if (mScrollListener instanceof OnWBScrollListener) {
			OnWBScrollListener l = (OnWBScrollListener) mScrollListener;
			l.onWBScrolling(this);
		}
	}

	private void updateHeaderHeight(float delta) {
		mPullRefreshView.setVisiableHeight((int) delta
				+ mPullRefreshView.getVisiableHeight());
		if (mEnablePullRefresh && !mPullRefreshing) { // 未处于刷新状态，更新箭头
			if (mPullRefreshView.getVisiableHeight() > mPullRefreshViewHeight) {
				mPullRefreshView.setState(PullRefreshView.STATE_READY);
			} else {
				mPullRefreshView.setState(PullRefreshView.STATE_NORMAL);
			}
		}
		setSelection(0);
	}

	/**
	 * 重置刷新视图高度
	 */
	private void resetHeaderHeight() {
		int height = mPullRefreshView.getVisiableHeight();
		if (height == 0)
			return;
		// 正在刷新，且刷新视图仅显示部分，不做处理
		if (mPullRefreshing && height <= mPullRefreshViewHeight) {
			return;
		}
		int finalHeight = 0;
		// 正在刷新，回退到仅显示刷新视图
		if (mPullRefreshing && height > mPullRefreshViewHeight) {
			finalHeight = mPullRefreshViewHeight;
		}
		mScrollBack = SCROLLBACK_HEADER;
		mScroller.startScroll(0, height, 0, finalHeight - height,
				SCROLL_DURATION);

		invalidate();
	}

	private void updateFooterHeight(float delta) {
		int height = mPullLoadView.getBottomMargin() + (int) delta;
		if (mEnablePullLoad && !mPullLoading) {
			if (height > PULL_LOAD_MORE_DELTA) {
				mPullLoadView.setState(LoadMoreView.STATE_READY);
			} else {
				mPullLoadView.setState(LoadMoreView.STATE_NORMAL);
			}
		}
		mPullLoadView.setBottomMargin(height);

		setSelection(mTotalItemCount - 1);
	}

	private void resetFooterHeight() {
		int bottomMargin = mPullLoadView.getBottomMargin();
		if (bottomMargin > 0) {
			mScrollBack = SCROLLBACK_FOOTER;
			mScroller.startScroll(0, bottomMargin, 0, -bottomMargin,
					SCROLL_DURATION);
			invalidate();
		}
	}

	private void startLoadMore() {
		mPullLoading = true;
		mPullLoadView.setState(LoadMoreView.STATE_LOADING);
		if (mPullRefreshCallbacker != null) {
			mPullRefreshCallbacker.onLoadMore();
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent ev) {
		if (mLastY == -1) {
			mLastY = ev.getRawY();
		}

		switch (ev.getAction()) {
		case MotionEvent.ACTION_DOWN:
			mLastY = ev.getRawY();
			break;
		case MotionEvent.ACTION_MOVE:
			final float deltaY = ev.getRawY() - mLastY;
			mLastY = ev.getRawY();
			if (getFirstVisiblePosition() == 0
					&& (mPullRefreshView.getVisiableHeight() > 0 || deltaY > 0)) {
				// 第一行，且下拉组件已显示或者正在下拉
				updateHeaderHeight(deltaY / OFFSET_RADIO);
				invokeOnScrolling();
			} else if (getLastVisiblePosition() == mTotalItemCount - 1
					&& (mPullLoadView.getBottomMargin() > 0 || deltaY < 0)) {
				// 最后一行，上来组件已显示或者正在上来
				updateFooterHeight(-deltaY / OFFSET_RADIO);
			}
			break;
		default:
			mLastY = -1;
			if (getFirstVisiblePosition() == 0) {
				// 触发刷新
				if (mEnablePullRefresh
						&& mPullRefreshView.getVisiableHeight() > mPullRefreshViewHeight) {
					mPullRefreshing = true;
					mPullRefreshView.setState(PullRefreshView.STATE_REFRESHING);
					if (mPullRefreshCallbacker != null) {
						mPullRefreshCallbacker.onRefresh();
					}
				}
				resetHeaderHeight();
			} else if (getLastVisiblePosition() == mTotalItemCount - 1) {
				// 触发载入更多
				if (mEnablePullLoad
						&& mPullLoadView.getBottomMargin() > PULL_LOAD_MORE_DELTA) {
					startLoadMore();
				}
				resetFooterHeight();
			}
			break;
		}
		return super.onTouchEvent(ev);
	}

	@Override
	public void computeScroll() {
		if (mScroller.computeScrollOffset()) {
			// Qzlog.d(TAG, "computeScroll:" + mScroller.getCurrY());
			if (mScrollBack == SCROLLBACK_HEADER) {
				mPullRefreshView.setVisiableHeight(mScroller.getCurrY());
			} else {
				mPullLoadView.setBottomMargin(mScroller.getCurrY());
			}
			postInvalidate();
			invokeOnScrolling();
		}
		super.computeScroll();
	}

	@Override
	public void setOnScrollListener(OnScrollListener l) {
		mScrollListener = l;
	}

	@Override
	public void onScrollStateChanged(AbsListView view, int scrollState) {
		if (mScrollListener != null) {
			mScrollListener.onScrollStateChanged(view, scrollState);
		}
	}

	@Override
	public void onScroll(AbsListView view, int firstVisibleItem,
			int visibleItemCount, int totalItemCount) {
		// send to user's listener
		mTotalItemCount = totalItemCount;
		if (mScrollListener != null) {
			mScrollListener.onScroll(view, firstVisibleItem, visibleItemCount,
					totalItemCount);
		}
	}

	public void setPullRefreshListener(IXListViewListener l) {
		mPullRefreshCallbacker = l;
	}

	// 滚动接口
	public interface OnWBScrollListener extends OnScrollListener {
		public void onWBScrolling(View view);
	}

	// 下拉刷新接口
	public interface IXListViewListener {
		public void onRefresh();

		public void onLoadMore();
	}
}
