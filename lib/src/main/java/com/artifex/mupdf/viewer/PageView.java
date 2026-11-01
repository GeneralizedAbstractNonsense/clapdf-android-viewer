package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.Quad;

import java.util.ArrayList;
import java.util.Iterator;

import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap.Config;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.FileUriExposedException;
import android.os.Handler;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.os.AsyncTask;

// Make our ImageViews opaque to optimize redraw
class OpaqueImageView extends ImageView {
	private RectF[] mOriginalColorAreas;
	private boolean mDarkMode;

	public OpaqueImageView(Context context) {
		super(context);
	}

	public void setOriginalColorAreas(RectF[] areas) {
		mOriginalColorAreas = areas;
		invalidate();
	}

	public void setDarkMode(boolean darkMode) {
		mDarkMode = darkMode;
		invalidate();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (!mDarkMode || mOriginalColorAreas == null || mOriginalColorAreas.length == 0)
			return;

		Drawable drawable = getDrawable();
		if (!(drawable instanceof BitmapDrawable))
			return;

		Bitmap bitmap = ((BitmapDrawable)drawable).getBitmap();
		Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
		int save = canvas.save();
		canvas.translate(getPaddingLeft(), getPaddingTop());
		canvas.concat(getImageMatrix());
		for (RectF area : mOriginalColorAreas) {
			int areaSave = canvas.save();
			canvas.clipRect(area);
			canvas.drawBitmap(bitmap, 0, 0, paint);
			canvas.restoreToCount(areaSave);
		}
		canvas.restoreToCount(save);
	}

	@Override
	public boolean isOpaque() {
		return true;
	}
}

public class PageView extends ViewGroup {
	private final String APP = "MuPDF";
	private final MuPDFCore mCore;

	private static final int HIGHLIGHT_COLOR = 0x80cc6600;
	private static final int TEXT_SELECTION_COLOR = 0x805B9BFF;
	private static final int LINK_COLOR = 0x800066cc;
	private static final int BOX_COLOR = 0xFF4444FF;
	private static final int BACKGROUND_COLOR = 0xFFFFFFFF;
	private static final int DARK_BACKGROUND_COLOR = Color.BLACK;
	private static final ColorMatrixColorFilter INVERT_COLOR_FILTER = new ColorMatrixColorFilter(new ColorMatrix(new float[] {
		-1, 0, 0, 0, 255,
		0, -1, 0, 0, 255,
		0, 0, -1, 0, 255,
		0, 0, 0, 1, 0
	}));
	private static final int PROGRESS_DIALOG_DELAY = 200;

	protected final Context mContext;

	protected     int       mPageNumber;
	private       Point     mParentSize;
	protected     Point     mSize;   // Size of page at minimum zoom
	protected     float     mSourceScale;

	private       ImageView mEntire; // Image rendered at minimum zoom
	private       Bitmap    mEntireBm;
	private       Matrix    mEntireMat;
	private       AsyncTask<Void,Void,Link[]> mGetLinkInfo;
	private       CancellableAsyncTask<Void, Boolean> mDrawEntire;

	private       Point     mPatchViewSize; // View size on the basis of which the patch was created
	private       Rect      mPatchArea;

	private       Bitmap    mPatchBm;
	private       Bitmap    mOldPatchBm;
	private       ImageView mPatch;

	private       CancellableAsyncTask<Void, Boolean> mDrawPatch;

	private       Quad      mSearchBoxes[][];
	private       Quad[]    mTextSelectionBoxes;
	private       com.artifex.mupdf.fitz.Point mSelectionAnchor;
	private       String    mSelectedText;
	protected     Link      mLinks[];
	private       View      mSearchView;
	private       boolean   mIsBlank;
	private       boolean   mHighlightLinks;
	private       boolean   mDarkMode;

	private       ImageView mErrorIndicator;

	private       ProgressBar mBusyIndicator;
	private final Handler   mHandler = new Handler();

	public PageView(Context c, MuPDFCore core, Point parentSize, Bitmap sharedHqBm1, Bitmap sharedHqBm2) {
		super(c);
		mContext = c;
		mCore = core;
		mParentSize = parentSize;
		setBackgroundColor(getPageBackgroundColor());
		mEntireBm = Bitmap.createBitmap(parentSize.x, parentSize.y, Config.ARGB_8888);
		mPatchBm = sharedHqBm1;
		mOldPatchBm = sharedHqBm2;
		mEntireMat = new Matrix();
	}

	public void setDarkMode(boolean darkMode) {
		mDarkMode = darkMode;
		ColorMatrixColorFilter filter = darkMode ? INVERT_COLOR_FILTER : null;
		if (mEntire != null) {
			((OpaqueImageView)mEntire).setDarkMode(darkMode);
			mEntire.setColorFilter(filter);
		}
		if (mPatch != null) {
			((OpaqueImageView)mPatch).setDarkMode(darkMode);
			mPatch.setColorFilter(filter);
		}
		if (mErrorIndicator != null)
			mErrorIndicator.setBackgroundColor(getPageBackgroundColor());
		setBackgroundColor(getPageBackgroundColor());
	}

	private int getPageBackgroundColor() {
		return mDarkMode ? DARK_BACKGROUND_COLOR : BACKGROUND_COLOR;
	}

	private void reinit() {
		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel();
			mDrawEntire = null;
		}

		if (mDrawPatch != null) {
			mDrawPatch.cancel();
			mDrawPatch = null;
		}

		if (mGetLinkInfo != null) {
			mGetLinkInfo.cancel(true);
			mGetLinkInfo = null;
		}

		mIsBlank = true;
		mPageNumber = 0;

		if (mSize == null)
			mSize = mParentSize;

		if (mEntire != null) {
			mEntire.setImageBitmap(null);
			((OpaqueImageView)mEntire).setOriginalColorAreas(null);
			mEntire.invalidate();
		}

		if (mPatch != null) {
			mPatch.setImageBitmap(null);
			((OpaqueImageView)mPatch).setOriginalColorAreas(null);
			mPatch.invalidate();
		}

		mPatchViewSize = null;
		mPatchArea = null;

		mSearchBoxes = null;
		mTextSelectionBoxes = null;
		mSelectionAnchor = null;
		mSelectedText = null;
		mLinks = null;
		if (mSearchView != null)
			mSearchView.invalidate();

		clearRenderError();
	}

	public void releaseResources() {
		reinit();

		if (mBusyIndicator != null) {
			removeView(mBusyIndicator);
			mBusyIndicator = null;
		}
		clearRenderError();
	}

	public void releaseBitmaps() {
		reinit();

		// recycle bitmaps before releasing them.

		if (mEntireBm!=null)
			mEntireBm.recycle();
		mEntireBm = null;

		if (mPatchBm!=null)
			mPatchBm.recycle();
		mPatchBm = null;

		if (mOldPatchBm!=null)
			mOldPatchBm.recycle();
		mOldPatchBm = null;
	}

	public void blank(int page) {
		reinit();
		mPageNumber = page;

		if (mBusyIndicator == null) {
			mBusyIndicator = new ProgressBar(mContext);
			mBusyIndicator.setIndeterminate(true);
			addView(mBusyIndicator);
		}

		setBackgroundColor(getPageBackgroundColor());
	}

	protected void clearRenderError() {
		if (mErrorIndicator == null)
			return;

		removeView(mErrorIndicator);
		mErrorIndicator = null;
		invalidate();
	}

	protected void setRenderError(String why) {

		int page = mPageNumber;
		reinit();
		mPageNumber = page;

		if (mBusyIndicator != null) {
			removeView(mBusyIndicator);
			mBusyIndicator = null;
		}
		if (mSearchView != null) {
			removeView(mSearchView);
			mSearchView = null;
		}

		if (mErrorIndicator == null) {
			mErrorIndicator = new OpaqueImageView(mContext);
			mErrorIndicator.setScaleType(ImageView.ScaleType.CENTER);
			addView(mErrorIndicator);
			Drawable mErrorIcon = getResources().getDrawable(R.drawable.ic_error_red_24dp);
			mErrorIndicator.setImageDrawable(mErrorIcon);
			mErrorIndicator.setBackgroundColor(getPageBackgroundColor());
		}

		setBackgroundColor(Color.TRANSPARENT);
		mErrorIndicator.bringToFront();
		mErrorIndicator.invalidate();
	}

	public void setPage(int page, PointF size) {
		// Cancel pending render task
		if (mDrawEntire != null) {
			mDrawEntire.cancel();
			mDrawEntire = null;
		}

		mIsBlank = false;
		// Highlights may be missing because mIsBlank was true on last draw
		if (mSearchView != null)
			mSearchView.invalidate();

		mPageNumber = page;

		if (size == null) {
			setRenderError("Error loading page");
			size = new PointF(612, 792);
		}

		// Calculate scaled size that fits within the screen limits
		// This is the size at minimum zoom
		mSourceScale = Math.min(mParentSize.x/size.x, mParentSize.y/size.y);
		Point newSize = new Point((int)(size.x*mSourceScale), (int)(size.y*mSourceScale));
		mSize = newSize;

		if (mErrorIndicator != null)
			return;

		if (mEntire == null) {
			mEntire = new OpaqueImageView(mContext);
			mEntire.setScaleType(ImageView.ScaleType.MATRIX);
			((OpaqueImageView)mEntire).setDarkMode(mDarkMode);
			mEntire.setColorFilter(mDarkMode ? INVERT_COLOR_FILTER : null);
			addView(mEntire);
		}

		mEntire.setImageBitmap(null);
		mEntire.invalidate();

		// Get the link info in the background
		mGetLinkInfo = new AsyncTask<Void,Void,Link[]>() {
			protected Link[] doInBackground(Void... v) {
				return getLinkInfo();
			}

			protected void onPostExecute(Link[] v) {
				mLinks = v;
				if (mSearchView != null)
					mSearchView.invalidate();
			}
		};

		mGetLinkInfo.execute();

		// Render the page in the background
		final RectF[][] entireImageAreas = new RectF[1][];
		mDrawEntire = new CancellableAsyncTask<Void, Boolean>(getDrawPageTask(mEntireBm, mSize.x, mSize.y, 0, 0, mSize.x, mSize.y, entireImageAreas)) {

			@Override
			public void onPreExecute() {
				setBackgroundColor(getPageBackgroundColor());
				mEntire.setImageBitmap(null);
				mEntire.invalidate();

				if (mBusyIndicator == null) {
					mBusyIndicator = new ProgressBar(mContext);
					mBusyIndicator.setIndeterminate(true);
					addView(mBusyIndicator);
					mBusyIndicator.setVisibility(INVISIBLE);
					mHandler.postDelayed(new Runnable() {
						public void run() {
							if (mBusyIndicator != null)
								mBusyIndicator.setVisibility(VISIBLE);
						}
					}, PROGRESS_DIALOG_DELAY);
				}
			}

			@Override
			public void onPostExecute(Boolean result) {
				removeView(mBusyIndicator);
				mBusyIndicator = null;
				if (result.booleanValue()) {
					clearRenderError();
					((OpaqueImageView)mEntire).setOriginalColorAreas(entireImageAreas[0]);
					mEntire.setImageBitmap(mEntireBm);
					mEntire.invalidate();
				} else {
					setRenderError("Error rendering page");
				}
				setBackgroundColor(Color.TRANSPARENT);
			}
		};

		mDrawEntire.execute();

		if (mSearchView == null) {
			mSearchView = new View(mContext) {
				@Override
				protected void onDraw(final Canvas canvas) {
					super.onDraw(canvas);
					// Work out current total scale factor
					// from source to view
					final float scale = mSourceScale*(float)getWidth()/(float)mSize.x;
					final Paint paint = new Paint();

					if (!mIsBlank && mSearchBoxes != null) {
						paint.setColor(HIGHLIGHT_COLOR);
						for (Quad[] searchBox : mSearchBoxes) {
							for (Quad q : searchBox) {
								Path path = new Path();
								path.moveTo(q.ul_x * scale, q.ul_y * scale);
								path.lineTo(q.ll_x * scale, q.ll_y * scale);
								path.lineTo(q.lr_x * scale, q.lr_y * scale);
								path.lineTo(q.ur_x * scale, q.ur_y * scale);
								path.close();
								canvas.drawPath(path, paint);
							}
						}
					}

					if (!mIsBlank && mTextSelectionBoxes != null) {
						paint.setColor(TEXT_SELECTION_COLOR);
						for (Quad q : mTextSelectionBoxes) {
							Path path = new Path();
							path.moveTo(q.ul_x * scale, q.ul_y * scale);
							path.lineTo(q.ll_x * scale, q.ll_y * scale);
							path.lineTo(q.lr_x * scale, q.lr_y * scale);
							path.lineTo(q.ur_x * scale, q.ur_y * scale);
							path.close();
							canvas.drawPath(path, paint);
						}
					}

					if (!mIsBlank && mLinks != null && mHighlightLinks) {
						paint.setColor(LINK_COLOR);
						for (Link link : mLinks)
							canvas.drawRect(link.getBounds().x0*scale, link.getBounds().y0*scale,
									link.getBounds().x1*scale, link.getBounds().y1*scale,
									paint);
					}
				}
			};

			addView(mSearchView);
		}
		requestLayout();
	}

	public void setSearchBoxes(Quad searchBoxes[][]) {
		mSearchBoxes = searchBoxes;
		if (mSearchView != null)
			mSearchView.invalidate();
	}

	public boolean startTextSelection(float x, float y) {
		if (mIsBlank || mSize == null || mSize.x == 0 || getWidth() == 0 || mSourceScale == 0)
			return false;
		com.artifex.mupdf.fitz.Point point = pagePoint(x, y);
		MuPDFCore.TextSelection selection = mCore.selectText(mPageNumber,
				new com.artifex.mupdf.fitz.Point(point), new com.artifex.mupdf.fitz.Point(point),
				com.artifex.mupdf.fitz.StructuredText.SELECT_WORDS);
		if (selection == null)
			return false;

		mSelectionAnchor = selection.start;
		setTextSelection(selection);
		return true;
	}

	public boolean extendTextSelection(float x, float y) {
		if (mSelectionAnchor == null)
			return false;

		MuPDFCore.TextSelection selection = mCore.selectText(mPageNumber,
				new com.artifex.mupdf.fitz.Point(mSelectionAnchor), pagePoint(x, y),
				com.artifex.mupdf.fitz.StructuredText.SELECT_WORDS);
		if (selection == null)
			return false;

		setTextSelection(selection);
		return true;
	}

	private com.artifex.mupdf.fitz.Point pagePoint(float x, float y) {
		float scale = mSourceScale * (float)getWidth() / (float)mSize.x;
		return new com.artifex.mupdf.fitz.Point((x - getLeft()) / scale, (y - getTop()) / scale);
	}

	private void setTextSelection(MuPDFCore.TextSelection selection) {
		mTextSelectionBoxes = selection.quads;
		mSelectedText = selection.text;
		if (mSearchView != null)
			mSearchView.invalidate();
	}

	public String getSelectedText() {
		return mSelectedText;
	}

	public boolean hasTextSelection() {
		return mSelectedText != null;
	}

	public void clearTextSelection() {
		mTextSelectionBoxes = null;
		mSelectionAnchor = null;
		mSelectedText = null;
		if (mSearchView != null)
			mSearchView.invalidate();
	}

	public void setLinkHighlighting(boolean f) {
		mHighlightLinks = f;
		if (mSearchView != null)
			mSearchView.invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int x, y;
		switch(View.MeasureSpec.getMode(widthMeasureSpec)) {
		case View.MeasureSpec.UNSPECIFIED:
			x = mSize.x;
			break;
		default:
			x = View.MeasureSpec.getSize(widthMeasureSpec);
		}
		switch(View.MeasureSpec.getMode(heightMeasureSpec)) {
		case View.MeasureSpec.UNSPECIFIED:
			y = mSize.y;
			break;
		default:
			y = View.MeasureSpec.getSize(heightMeasureSpec);
		}

		setMeasuredDimension(x, y);

		if (mBusyIndicator != null) {
			int limit = Math.min(mParentSize.x, mParentSize.y)/2;
			mBusyIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
		}
		if (mErrorIndicator != null) {
			int limit = Math.min(mParentSize.x, mParentSize.y)/2;
			mErrorIndicator.measure(View.MeasureSpec.AT_MOST | limit, View.MeasureSpec.AT_MOST | limit);
		}
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		int w = right-left;
		int h = bottom-top;

		if (mEntire != null) {
			if (mEntire.getWidth() != w || mEntire.getHeight() != h) {
				mEntireMat.setScale(w/(float)mSize.x, h/(float)mSize.y);
				mEntire.setImageMatrix(mEntireMat);
				mEntire.invalidate();
			}
			mEntire.layout(0, 0, w, h);
		}

		if (mSearchView != null) {
			mSearchView.layout(0, 0, w, h);
		}

		if (mPatchViewSize != null) {
			if (mPatchViewSize.x != w || mPatchViewSize.y != h) {
				// Zoomed since patch was created
				mPatchViewSize = null;
				mPatchArea = null;
				if (mPatch != null) {
					mPatch.setImageBitmap(null);
					mPatch.invalidate();
				}
			} else {
				mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
			}
		}

		if (mBusyIndicator != null) {
			int bw = mBusyIndicator.getMeasuredWidth();
			int bh = mBusyIndicator.getMeasuredHeight();

			mBusyIndicator.layout((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2);
		}

		if (mErrorIndicator != null) {
			int bw = (int) (8.5 * mErrorIndicator.getMeasuredWidth());
			int bh = (int) (11 * mErrorIndicator.getMeasuredHeight());
			mErrorIndicator.layout((w-bw)/2, (h-bh)/2, (w+bw)/2, (h+bh)/2);
		}
	}

	public void updateHq() {
		if (mErrorIndicator != null) {
			if (mPatch != null) {
				mPatch.setImageBitmap(null);
				mPatch.invalidate();
			}
			return;
		}

		Rect viewArea = new Rect(getLeft(),getTop(),getRight(),getBottom());
		if (viewArea.width() == mSize.x || viewArea.height() == mSize.y) {
			// If the viewArea's size matches the unzoomed size, there is no need for an hq patch
			if (mPatch != null) {
				mPatch.setImageBitmap(null);
				mPatch.invalidate();
			}
		} else {
			final Point patchViewSize = new Point(viewArea.width(), viewArea.height());
			final Rect patchArea = new Rect(0, 0, mParentSize.x, mParentSize.y);

			// Intersect and test that there is an intersection
			if (!patchArea.intersect(viewArea))
				return;

			// Offset patch area to be relative to the view top left
			patchArea.offset(-viewArea.left, -viewArea.top);

			boolean area_unchanged = patchArea.equals(mPatchArea) && patchViewSize.equals(mPatchViewSize);

			// If being asked for the same area as last time
			if (area_unchanged)
				return;

			if (mDrawPatch != null) {
				// Stop the drawing of previous patch if still going
				mDrawPatch.cancel();
				mDrawPatch = null;
			} else {
				// Swap old and new for the background render
				Bitmap swapPatchBm = mPatchBm;
				mPatchBm = mOldPatchBm;
				mOldPatchBm = swapPatchBm;
			}

			if (mPatch == null) {
				// Create the mPatch image view if not already done
				mPatch = new OpaqueImageView(mContext);
				mPatch.setScaleType(ImageView.ScaleType.MATRIX);
				((OpaqueImageView)mPatch).setDarkMode(mDarkMode);
				mPatch.setColorFilter(mDarkMode ? INVERT_COLOR_FILTER : null);
				addView(mPatch);

				if (mSearchView != null)
					mSearchView.bringToFront();
			}

			CancellableTaskDefinition<Void, Boolean> task;

			final RectF[][] patchImageAreas = new RectF[1][];
			task = getDrawPageTask(mPatchBm, patchViewSize.x, patchViewSize.y,
							patchArea.left, patchArea.top,
							patchArea.width(), patchArea.height(), patchImageAreas);

			mDrawPatch = new CancellableAsyncTask<Void, Boolean>(task) {

				public void onPostExecute(Boolean result) {
					if (result.booleanValue()) {
						mPatchViewSize = patchViewSize;
						mPatchArea = patchArea;
						clearRenderError();
						((OpaqueImageView)mPatch).setOriginalColorAreas(patchImageAreas[0]);
						mPatch.setImageBitmap(mPatchBm);
						mPatch.invalidate();

						//requestLayout();
						// Calling requestLayout here doesn't lead to a later call to layout. No idea
						// why, but apparently others have run into the problem.
						mPatch.layout(mPatchArea.left, mPatchArea.top, mPatchArea.right, mPatchArea.bottom);
					} else {
						setRenderError("Error rendering patch");
					}
					mDrawPatch = null;
				}
			};

			Log.e(APP, "execute task");
			mDrawPatch.execute();
		}
	}

	public void removeHq() {
			// Stop the drawing of the patch if still going
			if (mDrawPatch != null) {
				mDrawPatch.cancel();
				mDrawPatch = null;
			}

			// And get rid of it
			mPatchViewSize = null;
			mPatchArea = null;
			if (mPatch != null) {
				mPatch.setImageBitmap(null);
				mPatch.invalidate();
				removeView(mPatch);
			}
	}

	public int getPage() {
		return mPageNumber;
	}

	@Override
	public boolean isOpaque() {
		return true;
	}

	public int hitLink(Link link) {
		if (link.isExternal()) {
			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link.getURI()));
			intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET); // API>=21: FLAG_ACTIVITY_NEW_DOCUMENT
			try {
				mContext.startActivity(intent);
			} catch (FileUriExposedException x) {
				Log.e(APP, x.toString());
				Toast.makeText(getContext(), getResources().getString(R.string.toast_file_uris_not_allowed) + link.getURI(), Toast.LENGTH_LONG).show();
			} catch (Throwable x) {
				Log.e(APP, x.toString());
				Toast.makeText(getContext(), x.getMessage(), Toast.LENGTH_LONG).show();
			}
			return 0;
		} else {
			return mCore.resolveLink(link);
		}
	}

	public int hitLink(float x, float y) {
		// Since link highlighting was implemented, the super class
		// PageView has had sufficient information to be able to
		// perform this method directly. Making that change would
		// make MuPDFCore.hitLinkPage superfluous.
		float scale = mSourceScale*(float)getWidth()/(float)mSize.x;
		float docRelX = (x - getLeft())/scale;
		float docRelY = (y - getTop())/scale;

		if (mLinks != null)
			for (Link l: mLinks)
				if (l.getBounds().contains(docRelX, docRelY))
					return hitLink(l);
		return 0;
	}

	protected CancellableTaskDefinition<Void, Boolean> getDrawPageTask(final Bitmap bm, final int sizeX, final int sizeY,
			final int patchX, final int patchY, final int patchWidth, final int patchHeight,
			final RectF[][] imageAreas) {
		return new MuPDFCancellableTaskDefinition<Void, Boolean>() {
			@Override
			public Boolean doInBackground(Cookie cookie, Void ... params) {
				if (bm == null)
					return new Boolean(false);
				// Workaround bug in Android Honeycomb 3.x, where the bitmap generation count
				// is not incremented when drawing.
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB &&
						Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH)
					bm.eraseColor(0);
				try {
					imageAreas[0] = mCore.drawPageWithImageAreas(bm, mPageNumber, sizeX, sizeY,
							patchX, patchY, patchWidth, patchHeight, cookie);
					return new Boolean(true);
				} catch (RuntimeException e) {
					return new Boolean(false);
				}
			}
		};

	}

	protected Link[] getLinkInfo() {
		try {
			return mCore.getPageLinks(mPageNumber);
		} catch (RuntimeException e) {
			return null;
		}
	}
}
