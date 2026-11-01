package com.artifex.mupdf.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class TextDocumentConverter {
	private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";
	private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
	private static final String OFFICE_NS = "urn:oasis:names:tc:opendocument:xmlns:office:1.0";

	private TextDocumentConverter() {
	}

	static void convertCsv(InputStream input, OutputStream output) throws IOException {
		BufferedReader bufferedReader = new BufferedReader(
				new InputStreamReader(input, StandardCharsets.UTF_8));
		char delimiter = detectDelimiter(bufferedReader);
		PushbackReader reader = new PushbackReader(bufferedReader, 1);
		BufferedWriter writer = new BufferedWriter(
				new OutputStreamWriter(output, StandardCharsets.UTF_8));

		writeHtmlStart(writer);

		boolean inQuotes = false;
		boolean rowStarted = false;
		boolean fieldStarted = false;
		int firstChar = reader.read();
		if (firstChar == 0xfeff)
			firstChar = reader.read();
		int c = firstChar;

		while (c != -1) {
			if (!rowStarted) {
				writer.write("<p class=\"csv-row\">");
				rowStarted = true;
				fieldStarted = false;
			}

			if (c == '"') {
				if (inQuotes) {
					int next = reader.read();
					if (next == '"')
						writer.write("&quot;");
					else {
						inQuotes = false;
						if (next != -1)
							reader.unread(next);
					}
				} else if (!fieldStarted) {
					inQuotes = true;
				} else {
					writer.write("&quot;");
				}
			} else if (!inQuotes && c == delimiter) {
				writer.write(" | ");
				fieldStarted = false;
			} else if (!inQuotes && (c == '\n' || c == '\r')) {
				writer.write("</p>");
				if (c == '\r') {
					int next = reader.read();
					if (next != '\n' && next != -1)
						reader.unread(next);
				}
				rowStarted = false;
				fieldStarted = false;
			} else if (inQuotes && (c == '\n' || c == '\r')) {
				writer.write("<br/>");
				if (c == '\r') {
					int next = reader.read();
					if (next != '\n' && next != -1)
						reader.unread(next);
				}
			} else {
				writeEscaped(writer, (char)c);
				if (!Character.isWhitespace(c))
					fieldStarted = true;
			}
			c = reader.read();
		}

		if (rowStarted)
			writer.write("</p>");
		writer.write("</body></html>");
		writer.flush();
	}

	static void convertOdt(InputStream input, OutputStream output) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(input)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if ("content.xml".equals(entry.getName())) {
					convertOdtContent(zip, output);
					return;
				}
			}
		}
		throw new IOException("ODT document does not contain content.xml");
	}

	private static void convertOdtContent(InputStream input, OutputStream output) throws IOException {
		BufferedWriter writer = new BufferedWriter(
				new OutputStreamWriter(output, StandardCharsets.UTF_8));
		XmlPullParser parser = Xml.newPullParser();
		Deque<String> openTags = new ArrayDeque<>();
		boolean inBody = false;

		try {
			parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
			parser.setInput(input, null);
			writeHtmlStart(writer);
			int event = parser.getEventType();
			while (event != XmlPullParser.END_DOCUMENT) {
				String name = parser.getName();
				String namespace = parser.getNamespace();
				if (event == XmlPullParser.START_TAG) {
					if (OFFICE_NS.equals(namespace) && "body".equals(name)) {
						inBody = true;
					} else if (inBody) {
						writeOdtStartTag(parser, writer, openTags);
					}
				} else if (event == XmlPullParser.TEXT && inBody) {
					writeEscaped(writer, parser.getText());
				} else if (event == XmlPullParser.END_TAG) {
					if (inBody)
						writeOdtEndTag(namespace, name, writer, openTags);
					if (OFFICE_NS.equals(namespace) && "body".equals(name))
						inBody = false;
				}
				event = parser.next();
			}
			while (!openTags.isEmpty())
				writer.write("</" + openTags.pop() + ">");
			writer.write("</body></html>");
			writer.flush();
		} catch (XmlPullParserException e) {
			throw new IOException("Unable to parse ODT content.xml", e);
		}
	}

	private static void writeOdtStartTag(XmlPullParser parser, BufferedWriter writer,
			Deque<String> openTags) throws IOException {
		String name = parser.getName();
		String namespace = parser.getNamespace();
		String tag = null;

		if (TEXT_NS.equals(namespace)) {
			switch (name) {
			case "p":
				tag = "p";
				break;
			case "h":
				tag = "h2";
				break;
			case "span":
				tag = "span";
				break;
			case "list":
				tag = "ul";
				break;
			case "list-item":
				tag = "li";
				break;
			case "s":
				String count = parser.getAttributeValue(TEXT_NS, "c");
				int spaces = 1;
				try {
					if (count != null)
						spaces = Math.max(1, Math.min(1000, Integer.parseInt(count)));
				} catch (NumberFormatException ignored) {
					spaces = 1;
				}
				for (int i = 0; i < spaces; i++)
					writer.write("&nbsp;");
				return;
			case "tab":
				writer.write("&nbsp;&nbsp;&nbsp;&nbsp;");
				return;
			case "line-break":
				writer.write("<br/>");
				return;
			default:
				break;
			}
		} else if (TABLE_NS.equals(namespace)) {
			switch (name) {
			case "table":
				tag = "table";
				break;
			case "table-row":
				tag = "tr";
				break;
			case "table-cell":
				tag = "td";
				break;
			case "covered-table-cell":
				tag = "td";
				break;
			case "table-header-rows":
				return;
			default:
				break;
			}
		}

		if (tag != null) {
			writer.write("<" + tag + ">");
			openTags.push(tag);
		}
	}

	private static void writeOdtEndTag(String namespace, String name, BufferedWriter writer,
			Deque<String> openTags) throws IOException {
		if (TEXT_NS.equals(namespace) || TABLE_NS.equals(namespace)) {
			String tag = null;
			if (TEXT_NS.equals(namespace)) {
				switch (name) {
				case "p":
					tag = "p";
					break;
				case "h":
					tag = "h2";
					break;
				case "span":
					tag = "span";
					break;
				case "list":
					tag = "ul";
					break;
				case "list-item":
					tag = "li";
					break;
				default:
					break;
				}
			} else {
				switch (name) {
				case "table":
					tag = "table";
					break;
				case "table-row":
					tag = "tr";
					break;
				case "table-cell":
				case "covered-table-cell":
					tag = "td";
					break;
				default:
					break;
				}
			}
			if (tag != null && !openTags.isEmpty()) {
				writer.write("</" + tag + ">");
				openTags.pop();
			}
		}
	}

	private static char detectDelimiter(BufferedReader reader) throws IOException {
		reader.mark(8192);
		char[] sample = new char[8192];
		int length = reader.read(sample);
		reader.reset();
		int[] counts = new int[3];
		boolean inQuotes = false;
		for (int i = 0; i < length; i++) {
			char c = sample[i];
			if (c == '"') {
				if (inQuotes && i + 1 < length && sample[i + 1] == '"')
					i++;
				else
					inQuotes = !inQuotes;
			} else if (!inQuotes && (c == '\n' || c == '\r')) {
				break;
			} else if (!inQuotes) {
				if (c == ',')
					counts[0]++;
				else if (c == ';')
					counts[1]++;
				else if (c == '\t')
					counts[2]++;
			}
		}
		int best = 0;
		for (int i = 1; i < counts.length; i++)
			if (counts[i] > counts[best])
				best = i;
		return best == 1 ? ';' : best == 2 ? '\t' : ',';
	}

	private static void writeHtmlStart(BufferedWriter writer) throws IOException {
		writer.write("<!doctype html><html><head><meta charset=\"utf-8\"><style>"
				+ "body{font-family:sans-serif;margin:1em;white-space:pre-wrap;overflow-wrap:break-word}"
				+ ".csv-row{border-bottom:1px solid #888;padding:.35em 0;margin:.25em 0;overflow-wrap:break-word}"
				+ "table{border-collapse:collapse;width:100%}"
				+ "td{border:1px solid #888;padding:4px;vertical-align:top}"
				+ "p{margin:.5em 0}ul{padding-left:2em}"
				+ "</style></head><body>");
	}

	private static void writeEscaped(BufferedWriter writer, char c) throws IOException {
		switch (c) {
		case '&':
			writer.write("&amp;");
			break;
		case '<':
			writer.write("&lt;");
			break;
		case '>':
			writer.write("&gt;");
			break;
		default:
			writer.write(c);
		}
	}

	private static void writeEscaped(BufferedWriter writer, String text) throws IOException {
		for (int i = 0; i < text.length(); i++)
			writeEscaped(writer, text.charAt(i));
	}
}
