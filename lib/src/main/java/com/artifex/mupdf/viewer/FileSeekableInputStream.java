package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.SeekableInputStream;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

final class FileSeekableInputStream implements SeekableInputStream, Closeable {
	private final FileInputStream input;
	private final FileChannel channel;

	FileSeekableInputStream(File file) throws IOException {
		input = new FileInputStream(file);
		channel = input.getChannel();
	}

	@Override
	public long seek(long offset, int whence) throws IOException {
		long position;
		switch (whence) {
		case SEEK_SET:
			position = offset;
			break;
		case SEEK_CUR:
			position = channel.position() + offset;
			break;
		case SEEK_END:
			position = channel.size() + offset;
			break;
		default:
			throw new IOException("Unknown seek mode: " + whence);
		}
		if (position < 0)
			throw new IOException("Cannot seek before the beginning of the file");
		channel.position(position);
		return position;
	}

	@Override
	public long position() throws IOException {
		return channel.position();
	}

	@Override
	public int read(byte[] buffer) throws IOException {
		return input.read(buffer);
	}

	@Override
	public void close() throws IOException {
		input.close();
	}
}
