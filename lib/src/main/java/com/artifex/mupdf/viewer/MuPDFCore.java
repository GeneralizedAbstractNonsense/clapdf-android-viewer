package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.DisplayList;
import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Outline;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Point;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.RectI;
import com.artifex.mupdf.fitz.SeekableInputStream;
import com.artifex.mupdf.fitz.StructuredText;
import com.artifex.mupdf.fitz.StructuredTextWalker;
import com.artifex.mupdf.fitz.StructuredTextWalker.VectorInfo;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;

public class MuPDFCore
{
	private final String APP = "MuPDF";

	private final int MAXIMUM_OUTLINE_ITEMS = 1000;
	private final int MAXIMUM_OUTLINE_DEPTH = 4;

	private int resolution;
	private Document doc;
	private Outline[] outline;
	private int pageCount = -1;
	private boolean reflowable = false;
	private int currentPage;
	private Page page;
	private StructuredText structuredText;
	private int structuredTextPage = -1;
	private float pageWidth;
	private float pageHeight;
	private DisplayList displayList;
	private final HashMap<Integer, ArrayList<float[]>> pageImageBounds = new HashMap<Integer, ArrayList<float[]>>();

	/* Default to "A Format" pocket book size. */
	private int layoutW = 312;
	private int layoutH = 504;
	private int layoutEM = 10;

	private boolean outlineTruncated;

	private MuPDFCore(Document doc) {
		this.doc = doc;
		doc.layout(layoutW, layoutH, layoutEM);
		pageCount = doc.countPages();
		reflowable = doc.isReflowable();
		resolution = 160;
		currentPage = -1;
	}

	public MuPDFCore(byte buffer[], String magic) {
		this(Document.openDocument(buffer, magic));
	}

	public MuPDFCore(SeekableInputStream stm, String magic) {
		this(Document.openDocument(stm, magic));
	}

	public String getTitle() {
		return doc.getMetaData(Document.META_INFO_TITLE);
	}

	public int countPages() {
		return pageCount;
	}

	public boolean isReflowable() {
		return reflowable;
	}

	public synchronized int layout(int oldPage, int w, int h, int em) {
		if (w != layoutW || h != layoutH || em != layoutEM) {
			System.out.println("LAYOUT: " + w + "," + h);
			layoutW = w;
			layoutH = h;
			layoutEM = em;
			long mark = doc.makeBookmark(doc.locationFromPageNumber(oldPage));
			doc.layout(layoutW, layoutH, layoutEM);
			clearStructuredText();
			currentPage = -1;
			pageCount = doc.countPages();
			pageImageBounds.clear();
			outline = null;
			try {
				outline = doc.loadOutline();
			} catch (Exception ex) {
				/* ignore error */
			}
			return doc.pageNumberFromLocation(doc.findBookmark(mark));
		}
		return oldPage;
	}

	private synchronized void gotoPage(int pageNum) {
		/* TODO: page cache */
		if (pageNum > pageCount-1)
			pageNum = pageCount-1;
		else if (pageNum < 0)
			pageNum = 0;
		if (pageNum != currentPage) {
			clearStructuredText();
			if (page != null)
				page.destroy();
			page = null;
			if (displayList != null)
				displayList.destroy();
			displayList = null;
			page = null;
			pageWidth = 0;
			pageHeight = 0;
			currentPage = -1;

			if (doc != null) {
				page = doc.loadPage(pageNum);
				Rect b = page.getBounds();
				pageWidth = b.x1 - b.x0;
				pageHeight = b.y1 - b.y0;
			}

			currentPage = pageNum;
		}
	}

	public synchronized PointF getPageSize(int pageNum) {
		gotoPage(pageNum);
		return new PointF(pageWidth, pageHeight);
	}

	public synchronized Rect getPageBounds(int pageNum) {
		gotoPage(pageNum);
		return page != null ? new Rect(page.getBounds()) : Rect.Empty();
	}

	public synchronized void onDestroy() {
		clearStructuredText();
		if (displayList != null)
			displayList.destroy();
		displayList = null;
		pageImageBounds.clear();
		if (page != null)
			page.destroy();
		page = null;
		if (doc != null)
			doc.destroy();
		doc = null;
	}

	public synchronized void drawPage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		gotoPage(pageNum);

		if (displayList == null && page != null)
			try {
				displayList = page.toDisplayList();
			} catch (Exception ex) {
				displayList = null;
			}

		if (displayList == null || page == null)
			return;

		Matrix ctm = getPageTransform(pageW, pageH);

		AndroidDrawDevice dev = new AndroidDrawDevice(bm, patchX, patchY);
		try {
			displayList.run(dev, ctm, cookie);
			dev.close();
		} finally {
			dev.destroy();
		}
	}

	public synchronized RectF[] drawPageWithImageAreas(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		drawPage(bm, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, cookie);
		gotoPage(pageNum);
		if (page == null)
			return new RectF[0];

		ArrayList<float[]> bounds = pageImageBounds.get(pageNum);
		if (bounds == null) {
			bounds = new ArrayList<float[]>();
			final ArrayList<float[]> imageBounds = bounds;
			StructuredText text = null;
			try {
				text = page.toStructuredText();
				text.walk(new StructuredTextWalker() {
					public void onImageBlock(Rect bbox, Matrix transform, com.artifex.mupdf.fitz.Image image) {
						imageBounds.add(new float[] {bbox.x0, bbox.y0, bbox.x1, bbox.y1});
					}

					public void beginTextBlock(Rect bbox, int flags) {}
					public void endTextBlock() {}
					public void beginLine(Rect bbox, int wmode, com.artifex.mupdf.fitz.Point dir) {}
					public void endLine() {}
					public void onChar(int c, com.artifex.mupdf.fitz.Point origin,
							com.artifex.mupdf.fitz.Font font, float size, Quad quad,
							int argb, int flags, int bidi) {}
					public void beginStruct(String standard, String raw, int index) {}
					public void endStruct() {}
					public void onVector(Rect bbox, VectorInfo info, int argb) {}
				});
				pageImageBounds.put(pageNum, bounds);
			} catch (RuntimeException e) {
				bounds.clear();
				Rect pageBounds = page.getBounds();
				bounds.add(new float[] {pageBounds.x0, pageBounds.y0, pageBounds.x1, pageBounds.y1});
				pageImageBounds.put(pageNum, bounds);
			} finally {
				if (text != null)
					text.destroy();
			}
		}

		Matrix ctm = getPageTransform(pageW, pageH);
		RectF[] result = new RectF[bounds.size()];
		for (int i = 0; i < bounds.size(); i++) {
			float[] box = bounds.get(i);
			Rect image = new Rect(box[0], box[1], box[2], box[3]).transform(ctm);
			result[i] = new RectF(image.x0 - patchX - 1, image.y0 - patchY - 1,
					image.x1 - patchX + 1, image.y1 - patchY + 1);
		}
		return result;
	}

	private Matrix getPageTransform(int pageW, int pageH) {
		float zoom = resolution / 72;
		Matrix ctm = new Matrix(zoom, zoom);
		RectI bbox = new RectI(page.getBounds().transform(ctm));
		float xscale = (float)pageW / (float)(bbox.x1-bbox.x0);
		float yscale = (float)pageH / (float)(bbox.y1-bbox.y0);
		ctm.scale(xscale, yscale);
		return ctm;
	}

	public synchronized TextSelection selectText(int pageNum, Point start, Point end, int mode) {
		StructuredText text = getStructuredText(pageNum);
		if (text == null)
			return null;

		text.snapSelection(start, end, mode);
		Quad[] quads = text.highlight(start, end);
		if (quads == null || quads.length == 0)
			return null;
		return new TextSelection(new Point(start), new Point(end),
				quads);
	}

	public synchronized String copyText(int pageNum, Point start, Point end) {
		StructuredText text = getStructuredText(pageNum);
		return text != null ? text.copy(start, end) : null;
	}

	private StructuredText getStructuredText(int pageNum) {
		gotoPage(pageNum);
		if (page == null)
			return null;
		if (structuredText == null || structuredTextPage != pageNum) {
			clearStructuredText();
			structuredText = page.toStructuredText();
			structuredTextPage = pageNum;
		}
		return structuredText;
	}

	private void clearStructuredText() {
		if (structuredText != null)
			structuredText.destroy();
		structuredText = null;
		structuredTextPage = -1;
	}

	public static class TextSelection {
		public final Point start;
		public final Point end;
		public final Quad[] quads;

		TextSelection(Point start, Point end, Quad[] quads) {
			this.start = start;
			this.end = end;
			this.quads = quads;
		}
	}

	public synchronized void updatePage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		drawPage(bm, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, cookie);
	}

	public synchronized Link[] getPageLinks(int pageNum) {
		gotoPage(pageNum);
		return page != null ? page.getLinks() : null;
	}

	public synchronized int resolveLink(Link link) {
		return doc.pageNumberFromLocation(doc.resolveLink(link));
	}

	public synchronized Quad[][] searchPage(int pageNum, String text) {
		gotoPage(pageNum);
		return page.search(text);
	}

	public synchronized boolean hasOutline() {
		if (outline == null) {
			try {
				outline = doc.loadOutline();
			} catch (Exception ex) {
				/* ignore error */
			}
		}
		return outline != null;
	}

	private void flattenOutlineNodes(ArrayList<OutlineActivity.Item> result, Outline list[], String indent, int depth) {
		for (Outline node : list) {
			if (node.title != null) {
				int page = doc.pageNumberFromLocation(doc.resolveLink(node));
				if (result.size() >= MAXIMUM_OUTLINE_ITEMS)
					outlineTruncated = true;
				else
					result.add(new OutlineActivity.Item(indent + node.title, page));
			}
			if (node.down != null)
			{
				if (depth >= MAXIMUM_OUTLINE_DEPTH || result.size() >= MAXIMUM_OUTLINE_ITEMS)
					outlineTruncated = true;
				else
					flattenOutlineNodes(result, node.down, indent + "    ", depth + 1);
			}
		}
	}

	public synchronized ArrayList<OutlineActivity.Item> getOutline() {
		outlineTruncated = false;
		ArrayList<OutlineActivity.Item> result = new ArrayList<OutlineActivity.Item>();
		flattenOutlineNodes(result, outline, "", 0);
		return result;
	}

	public synchronized boolean wasOutlineTruncated() {
		return outlineTruncated;
	}

	public synchronized boolean needsPassword() {
		return doc.needsPassword();
	}

	public synchronized boolean authenticatePassword(String password) {
		boolean authenticated = doc.authenticatePassword(password);
		pageCount = doc.countPages();
		reflowable = doc.isReflowable();
		return authenticated;
	}
}
