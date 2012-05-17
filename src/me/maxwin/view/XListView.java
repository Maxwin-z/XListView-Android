/**
 * @file XListView.java
 * @package com.tencent.weibu.view
 * @create Mar 18, 2012 6:28:41 PM
 * @author maxiezhang@tencent.com
 * @description 添加了滚动触边时的回滚效果，暂时仅支持垂直滚动。
 */
package me.maxwin.view;

import me.maxwin.R;
import android.content.Context;
import android.util.AttributeSet;
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

	private float mLastY = -1; // save event y
	private Scroller mScroller; // used for scroll back
	private OnScrollListener mScrollListener; // user's scroll listener

	// -- 下拉刷新
	// 下拉刷新视图
	private XListViewHeader mHeaderView;
	// 刷新视图内如，用于计算高度，不启用下拉刷新时直接隐藏
	private RelativeLayout mHeaderViewContent;
	// 保存下拉刷新视图的高度
	private int mHeaderViewHeight;
	// 是否启用下拉刷新
	private boolean mEnablePullRefresh = true;
	// 当前是否处于下拉刷新状态
	private boolean mPullRefreshing = false;
	// 下拉刷新回调
	private IXListViewListener mListViewListener;

	// -- 上拉载入更多
	private XListViewFooter mFooterView;
	private RelativeLayout mFooterViewContent;
	private boolean mEnablePullLoad;
	private boolean mPullLoading;

	// 总item，用于判断是否触底
	private int mTotalItemCount;

	// 启动mScroller调整位置时，需判断是顶部或底部
	private int mScrollBack;
	private final static int SCROLLBACK_HEADER = 0;
	private final static int SCROLLBACK_FOOTER = 1;

	// 回滚动画时间
	private final static int SCROLL_DURATION = 400; // ms
	private final static int PULL_LOAD_MORE_DELTA = 50; // 上拉50px后提示
	private final static float OFFSET_RADIO = 1.8f;	// 实现下拉位置滞后效果
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
		// 将滚动回调绑定到this上，通过mWBScrollListner做proxy
		super.setOnScrollListener(this);

		// 初始化下拉刷新view
		mHeaderView = new XListViewHeader(context);
		mHeaderViewContent = (RelativeLayout) mHeaderView
				.findViewById(R.id.xlistview_header_content);
		addHeaderView(mHeaderView);

		// 初始化上拉载入更多
		mFooterView = new XListViewFooter(context);
		mFooterViewContent = (RelativeLayout) mFooterView
				.findViewById(R.id.xlistview_footer_content);

		// 初始化header height
		mHeaderView.getViewTreeObserver().addOnGlobalLayoutListener(
				new OnGlobalLayoutListener() {
					@Override
					public void onGlobalLayout() {
						mHeaderViewHeight = mHeaderViewContent
								.getHeight();
						getViewTreeObserver()
								.removeGlobalOnLayoutListener(this);
					}
				});
	}

	@Override
	public void setAdapter(ListAdapter adapter) {
		// test load more
		addFooterView(mFooterView);
		super.setAdapter(adapter);
	}

	/**
	 * 设置启用下拉刷新，禁用时下拉模块隐藏，不会触发刷新事件，但保留下拉回滚行为
	 * @param enable
	 */
	public void setPullRefreshEnable(boolean enable) {
		mEnablePullRefresh = enable;
		if (!mEnablePullRefresh) { // disable
			mHeaderViewContent.setVisibility(View.INVISIBLE);
		} else {
			mFooterViewContent.setVisibility(View.VISIBLE);
		}
	}

	/** 
	 * 设置启用上拉载入更多
	 * @param enable
	 */
	public void setPullLoadEnable(boolean enable) {
		mEnablePullLoad = enable;
		if (!mEnablePullLoad) {
			mFooterViewContent.setVisibility(View.INVISIBLE);
			mFooterView.setOnClickListener(null);
		} else {
			mPullLoading = false;
			mFooterViewContent.setVisibility(View.VISIBLE);
			mFooterView.setState(XListViewFooter.STATE_NORMAL);
			mFooterView.setOnClickListener(new OnClickListener() {
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
			mFooterView.setState(XListViewFooter.STATE_NORMAL);
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
		mHeaderView.setVisiableHeight((int) delta
				+ mHeaderView.getVisiableHeight());
		if (mEnablePullRefresh && !mPullRefreshing) { // 未处于刷新状态，更新箭头
			if (mHeaderView.getVisiableHeight() > mHeaderViewHeight) {
				mHeaderView.setState(XListViewHeader.STATE_READY);
			} else {
				mHeaderView.setState(XListViewHeader.STATE_NORMAL);
			}
		}
		setSelection(0);
	}

	/**
	 * 重置刷新视图高度
	 */
	private void resetHeaderHeight() {
		int height = mHeaderView.getVisiableHeight();
		if (height == 0)
			return;
		// 正在刷新，且刷新视图仅显示部分，不做处理
		if (mPullRefreshing && height <= mHeaderViewHeight) {
			return;
		}
		int finalHeight = 0;
		// 正在刷新，回退到仅显示刷新视图
		if (mPullRefreshing && height > mHeaderViewHeight) {
			finalHeight = mHeaderViewHeight;
		}
		mScrollBack = SCROLLBACK_HEADER;
		mScroller.startScroll(0, height, 0, finalHeight - height,
				SCROLL_DURATION);

		invalidate();
	}

	private void updateFooterHeight(float delta) {
		int height = mFooterView.getBottomMargin() + (int) delta;
		if (mEnablePullLoad && !mPullLoading) {
			if (height > PULL_LOAD_MORE_DELTA) {
				mFooterView.setState(XListViewFooter.STATE_READY);
			} else {
				mFooterView.setState(XListViewFooter.STATE_NORMAL);
			}
		}
		mFooterView.setBottomMargin(height);
		
		setSelection(mTotalItemCount - 1);
	}

	private void resetFooterHeight() {
		int bottomMargin = mFooterView.getBottomMargin();
		if (bottomMargin > 0) {
			mScrollBack = SCROLLBACK_FOOTER;
			mScroller.startScroll(0, bottomMargin, 0, -bottomMargin,
					SCROLL_DURATION);
			invalidate();
		}
	}
	
	private void startLoadMore() {
		mPullLoading = true;
		mFooterView.setState(XListViewFooter.STATE_LOADING);
		if (mListViewListener != null) {
			mListViewListener.onLoadMore();
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
					&& (mHeaderView.getVisiableHeight() > 0 || deltaY > 0)) {
				// 第一行，且下拉组件已显示或者正在下拉
				updateHeaderHeight(deltaY / OFFSET_RADIO);
				invokeOnScrolling();
			} else if (getLastVisiblePosition() == mTotalItemCount - 1
					&& (mFooterView.getBottomMargin() > 0 || deltaY < 0)) {
				// 最后一行，上来组件已显示或者正在上来
				updateFooterHeight(-deltaY / OFFSET_RADIO);
			}
			break;
		default:
			mLastY = -1;
			if (getFirstVisiblePosition() == 0) {
				// 触发刷新
				if (mEnablePullRefresh
						&& mHeaderView.getVisiableHeight() > mHeaderViewHeight) {
					mPullRefreshing = true;
					mHeaderView.setState(XListViewHeader.STATE_REFRESHING);
					if (mListViewListener != null) {
						mListViewListener.onRefresh();
					}
				}
				resetHeaderHeight();
			} else if (getLastVisiblePosition() == mTotalItemCount - 1) {
				// 触发载入更多
				if (mEnablePullLoad
						&& mFooterView.getBottomMargin() > PULL_LOAD_MORE_DELTA) {
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
				mHeaderView.setVisiableHeight(mScroller.getCurrY());
			} else {
				mFooterView.setBottomMargin(mScroller.getCurrY());
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

	public void setXListViewListener(IXListViewListener l) {
		mListViewListener = l;
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
