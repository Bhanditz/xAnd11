// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.monksanctum.xand11.graphics;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.util.Log;

import org.monksanctum.xand11.Client;
import org.monksanctum.xand11.Dispatcher;
import org.monksanctum.xand11.comm.BitmaskParser;
import org.monksanctum.xand11.comm.PacketReader;
import org.monksanctum.xand11.comm.PacketWriter;
import org.monksanctum.xand11.comm.Request;
import org.monksanctum.xand11.errors.DrawableError;
import org.monksanctum.xand11.errors.MatchError;
import org.monksanctum.xand11.errors.ValueError;
import org.monksanctum.xand11.errors.XError;
import org.monksanctum.xand11.fonts.Font;
import org.monksanctum.xand11.fonts.FontManager;

import static org.monksanctum.xand11.graphics.GraphicsManager.DEBUG;

public class GraphicsProtocol implements Dispatcher.PacketHandler {
    private static final String TAG = "GraphicsProtocol";

    private static final byte[] HANDLED_OPS = {
            Request.CREATE_GC,
            Request.CHANGE_GC,
            Request.FREE_GC,
            Request.CREATE_PIXMAP,
            Request.FREE_PIXMAP,
            Request.PUT_IMAGE,
            Request.GET_IMAGE,
            Request.GET_GEOMETRY,
            Request.QUERY_BEST_SIZE,
            Request.COPY_PLANE,
    };

    private final GraphicsManager mManager;
    private final FontManager mFontManager;

    public GraphicsProtocol(GraphicsManager manager, FontManager fontManager) {
        mManager = manager;
        mFontManager = fontManager;
    }

    @Override
    public byte[] getOpCodes() {
        return HANDLED_OPS;
    }

    @Override
    public void handleRequest(Client client, PacketReader reader, PacketWriter writer)
            throws XError {
        switch (reader.getMajorOpCode()) {
            case Request.CREATE_GC:
                handleCreateGc(reader);
                break;
            case Request.CHANGE_GC:
                handleChangeGc(reader);
                break;
            case Request.FREE_GC:
                handleFreeGc(reader);
                break;
            case Request.CREATE_PIXMAP:
                handleCreatePixmap(reader);
                break;
            case Request.FREE_PIXMAP:
                handleFreePixmap(reader);
                break;
            case Request.PUT_IMAGE:
                handlePutImage(reader);
                break;
            case Request.GET_IMAGE:
                handleGetImage(reader, writer);
                break;
            case Request.GET_GEOMETRY:
                handleGetGeometry(reader, writer);
                break;
            case Request.QUERY_BEST_SIZE:
                handleRequestBestSize(reader, writer);
                break;
            case Request.COPY_PLANE:
                handleCopyPlane(reader, writer);
                break;
        }
    }

    private void handleRequestBestSize(PacketReader reader, PacketWriter writer) {
        byte type = reader.getMinorOpCode();
        int drawable = reader.readCard32();
        int width = reader.readCard16();
        int height = reader.readCard16();

        writer.writeCard16(width);
        writer.writeCard16(height);
        writer.writePadding(20);
    }

    private void handleGetGeometry(PacketReader reader, PacketWriter writer) throws DrawableError {
        XDrawable drawable = mManager.getDrawable(reader.readCard32());
        synchronized (drawable) {
            writer.writeCard32(drawable.getParent().getId());
            writer.writeCard16(drawable.getX());
            writer.writeCard16(drawable.getY());
            writer.writeCard16(drawable.getWidth());
            writer.writeCard16(drawable.getHeight());
            writer.writeCard16(drawable.getBorderWidth());
            writer.writePadding(10);
        }
    }

    private void handlePutImage(PacketReader reader) throws XError {
        int drawableId = reader.readCard32();
        int gcontextId = reader.readCard32();
        int width = reader.readCard16();
        int height = reader.readCard16();
        int x = reader.readCard16(); // Should be Int16
        int y = reader.readCard16(); // Should be Int16
        byte leftPad = reader.readByte();
        byte depth = reader.readByte();
        byte format = reader.getMinorOpCode();
        reader.readPadding(2);
        if (format == ZPIXMAP) {
            if (leftPad != 0) {
                Log.d(TAG, "Invalid " + depth + " "+ leftPad);
                throw new MatchError(depth);
            }
        }
        if (depth != 1 && depth != 32) {
            throw new MatchError(depth);
        }
        GraphicsContext context = mManager.getGc(gcontextId);
        XDrawable drawable = mManager.getDrawable(drawableId);
        Bitmap bitmap = depth != 1 ? GraphicsUtils.readZBitmap(reader, width, height)
                : GraphicsUtils.readBitmap(reader, leftPad, width, height, depth, context);
        synchronized (drawable) {
            drawable.lockCanvas(context).drawBitmap(bitmap, x, y, context.getPaint());
            if (DEBUG) Log.d(TAG, "Drawing bitmap " + x + " " + y + " " + bitmap.getWidth() + " "
                    + bitmap.getHeight());
            drawable.unlockCanvas();
        }
    }

    private void handleGetImage(PacketReader reader, PacketWriter writer) throws XError {
        int drawableId = reader.readCard32();
        int x = reader.readCard16(); // Should be Int16
        int y = reader.readCard16(); // Should be Int16
        int width = reader.readCard16();
        int height = reader.readCard16();
        int planeMask = reader.readCard32();
        byte format = reader.getMinorOpCode();

        XDrawable drawable = mManager.getDrawable(drawableId);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        synchronized (drawable) {
            drawable.read(bitmap, x, y, width, height);
        }

        writer.setMinorOpCode((byte) drawable.getDepth());
        writer.writeCard32(drawableId);
        writer.writePadding(20);
        if (format == ZPIXMAP) {
            GraphicsUtils.writeZBitmap(writer, width, height, bitmap);
        } else {
            GraphicsUtils.writeBitmap(writer, (byte) 0, width, height, (byte) 32, null, bitmap);
        }
    }

    private void handleCreatePixmap(PacketReader reader) throws ValueError, DrawableError {
        int pixmap = reader.readCard32();
        // TODO: Validate drawable id.
        int drawable = reader.readCard32();
        byte depth = reader.getMinorOpCode();
        int width = reader.readCard16();
        int height = reader.readCard16();
        mManager.createPixmap(pixmap, depth, width, height, mManager.getDrawable(drawable));
    }

    private void handleFreePixmap(PacketReader reader) {
        int pixmap = reader.readCard32();
        mManager.freePixmap(pixmap);
    }

    private void handleCopyPlane(PacketReader reader, PacketWriter writer) throws XError {
        int srcDrawableId = reader.readCard32();
        int destDrawableId = reader.readCard32();
        int gcontextId = reader.readCard32();
        int srcX = reader.readInt16();
        int srcY = reader.readInt16();
        int destX = reader.readInt16();
        int destY = reader.readInt16();
        int width = reader.readCard16();
        int height = reader.readCard16();
        int bitPlane = reader.readCard32();

        XDrawable src = mManager.getDrawable(srcDrawableId);
        XDrawable dest = mManager.getDrawable(destDrawableId);
        GraphicsContext gc = mManager.getGc(gcontextId);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        synchronized (src) {
            src.read(bitmap, srcX, srcY, width, height);
        }
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                bitmap.setPixel(j, i, (bitmap.getPixel(j, i) & bitPlane) != 0
                        ? gc.foreground : gc.background);
            }
        }
        synchronized (dest) {
            Canvas c = dest.lockCanvas(gc);
            c.drawBitmap(bitmap, destX, destY, gc.getPaint());
            dest.unlockCanvas();
        }
    }

    public void handleChangeGc(final PacketReader reader) throws XError {
        final GraphicsContext gc = mManager.getGc(reader.readCard32());
        synchronized (gc) {
            parseGc(reader, gc);
            gc.createPaint(mFontManager);
            // Eat up the extra bytes so we don't get a warning.
            // This is expected to have some arbitrary padding.
            reader.readPadding(reader.getRemaining());
        }
    }

    public void handleCreateGc(final PacketReader reader) throws XError {
        // TODO (SPEC)
        int id = reader.readCard32();
        final GraphicsContext gc = mManager.createGc(id);
        synchronized (gc) {
            int drawable = reader.readCard32();
            gc.drawable = drawable;
            parseGc(reader, gc);
            gc.createPaint(mFontManager);
            // Eat up the extra bytes so we don't get a warning.
            // This is expected to have some arbitrary padding.
            reader.readPadding(reader.getRemaining());
        }
    }

    private void parseGc(PacketReader reader, GraphicsContext gc) throws XError {
        new BitmaskParser(reader.readCard32(), 0x400000) {
            @Override
            public void readValue(int mask) throws ValueError {
                switch (mask) {
                    case 0x01:
                        gc.function = reader.readByte();
                        break;
                    case 0x02:
                        gc.planeMask = reader.readCard32();
                        break;
                    case 0x04:
                        gc.foreground = reader.readCard32();
                        break;
                    case 0x08:
                        gc.background = reader.readCard32();
                        break;
                    case 0x10:
                        gc.lineWidth = reader.readCard16();
                        break;
                    case 0x20:
                        gc.lineStyle = reader.readByte();
                        break;
                    case 0x40:
                        gc.capStyle = reader.readByte();
                        break;
                    case 0x80:
                        gc.joinStyle = reader.readByte();
                        break;
                    case 0x100:
                        gc.fillStyle = reader.readByte();
                        break;
                    case 0x200:
                        gc.fillRule = reader.readByte();
                        break;
                    case 0x400:
                        gc.tile = reader.readCard32();
                        break;
                    case 0x800:
                        gc.stipple = reader.readCard32();
                        break;
                    case 0x1000:
                        gc.tileStippleX = reader.readCard16();
                        break;
                    case 0x2000:
                        gc.tileStippleY = reader.readCard16();
                        break;
                    case 0x4000:
                        gc.font = reader.readCard32();
                        break;
                    case 0x8000:
                        gc.subwindowMode = reader.readByte();
                        break;
                    case 0x10000:
                        gc.graphicsExposures = reader.readByte() != 0;
                        break;
                    case 0x20000:
                        gc.clipX = reader.readCard16();
                        break;
                    case 0x40000:
                        gc.clipY = reader.readCard16();
                        break;
                    case 0x80000:
                        gc.clipMask = reader.readCard32();
                        if (gc.clipMask != 0) {
                            throw new ValueError(gc.clipMask);
                        }
                        break;
                    case 0x100000:
                        gc.dashOffset = reader.readCard16();
                        break;
                    case 0x200000:
                        gc.dashes = reader.readByte();
                        break;
                    case 0x400000:
                        gc.arcMode = reader.readByte();
                        break;
                }
            }
        };
    }

    private void handleFreeGc(PacketReader reader) {
        int id = reader.readCard32();
        mManager.freeGc(id);
    }

    public static final int BITMAP = 0;
    public static final int XYPIXMAP = 1;
    public static final int ZPIXMAP = 2;
}
