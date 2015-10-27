/*
 * Copyright 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.common.detector;

import com.google.zxing.NotFoundException;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;

/**
 * <p>
 * Detects a candidate barcode-like rectangular region within an image. It
 * starts around the center of the image, increases the size of the candidate
 * region until it finds a white rectangular region. By keeping track of the
 * last black points it encountered, it determines the corners of the barcode.
 * </p>
 *
 * @author David Olivier
 */
public final class WhiteRectangleDetector {

  private static final int INIT_SIZE = 10;
  private static final int CORR = 0;

  private final BitMatrix image;
  private final int height;
  private final int width;
  private final int leftInit;
  private final int rightInit;
  private final int downInit;
  private final int upInit;

  public WhiteRectangleDetector(BitMatrix image) throws NotFoundException {
    this(image, INIT_SIZE, image.getWidth() / 2, image.getHeight() / 2);
  }

  /**
   * @param image barcode image to find a rectangle in
   * @param initSize initial size of search area around center
   * @param x x position of search center
   * @param y y position of search center
   * @throws NotFoundException if image is too small to accommodate {@code initSize}
   */
  public WhiteRectangleDetector(BitMatrix image, int initSize, int x, int y) throws NotFoundException {
    this.image = image;
    height = image.getHeight();
    width = image.getWidth();
    int halfsize = initSize / 2;
    leftInit = x - halfsize;
    rightInit = x + halfsize;
    upInit = y - halfsize;
    downInit = y + halfsize;
    if (upInit < 0 || leftInit < 0 || downInit >= height || rightInit >= width) {
      throw NotFoundException.getNotFoundInstance();
    }
  }

  /**
   * <p>
   * Detects a candidate barcode-like rectangular region within an image. It
   * starts around the center of the image, increases the size of the candidate
   * region until it finds a white rectangular region.
   * </p>
   *
   * @return {@link ResultPoint}[] describing the corners of the rectangular
   *         region. The first and last points are opposed on the diagonal, as
   *         are the second and third. The first point will be the topmost
   *         point and the last, the bottommost. The second point will be
   *         leftmost and the third, the rightmost
   * @throws NotFoundException if no Data Matrix Code can be found
   */
  public ResultPoint[] detect() throws NotFoundException {

    int left = leftInit;
    int right = rightInit;
    int up = upInit;
    int down = downInit;
    boolean sizeExceeded = false;
    boolean aBlackPointFoundOnBorder = true;
    boolean atLeastOneBlackPointFoundOnBorder = false;
    
    boolean atLeastOneBlackPointFoundOnRight = false;
    boolean atLeastOneBlackPointFoundOnBottom = false;
    boolean atLeastOneBlackPointFoundOnLeft = false;
    boolean atLeastOneBlackPointFoundOnTop = false;

    while (aBlackPointFoundOnBorder) {

      aBlackPointFoundOnBorder = false;

      // .....
      // .   |
      // .....
      boolean rightBorderNotWhite = true;
      while ((rightBorderNotWhite || !atLeastOneBlackPointFoundOnRight) && right < width) {
        rightBorderNotWhite = containsBlackPoint(up, down, right, false);
        if (rightBorderNotWhite) {
          right++;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnRight = true;
        } else if (!atLeastOneBlackPointFoundOnRight) {
          right++;
        }
      }

      if (right >= width) {
        sizeExceeded = true;
        break;
      }

      // .....
      // .   .
      // .___.
      boolean bottomBorderNotWhite = true;
      while ((bottomBorderNotWhite || !atLeastOneBlackPointFoundOnBottom) && down < height) {
        bottomBorderNotWhite = containsBlackPoint(left, right, down, true);
        if (bottomBorderNotWhite) {
          down++;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnBottom = true;
        } else if (!atLeastOneBlackPointFoundOnBottom) {
          down++;
        }
      }

      if (down >= height) {
        sizeExceeded = true;
        break;
      }

      // .....
      // |   .
      // .....
      boolean leftBorderNotWhite = true;
      while ((leftBorderNotWhite || !atLeastOneBlackPointFoundOnLeft) && left >= 0) {
        leftBorderNotWhite = containsBlackPoint(up, down, left, false);
        if (leftBorderNotWhite) {
          left--;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnLeft = true;
        } else if (!atLeastOneBlackPointFoundOnLeft) {
          left--;
        }
      }

      if (left < 0) {
        sizeExceeded = true;
        break;
      }

      // .___.
      // .   .
      // .....
      boolean topBorderNotWhite = true;
      while ((topBorderNotWhite  || !atLeastOneBlackPointFoundOnTop) && up >= 0) {
        topBorderNotWhite = containsBlackPoint(left, right, up, true);
        if (topBorderNotWhite) {
          up--;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnTop = true;
        } else if (!atLeastOneBlackPointFoundOnTop) {
          up--;
        }
      }

      if (up < 0) {
        sizeExceeded = true;
        break;
      }

      if (aBlackPointFoundOnBorder) {
        atLeastOneBlackPointFoundOnBorder = true;
      }

    }

    if (!sizeExceeded && atLeastOneBlackPointFoundOnBorder) {

      int maxSize = right - left;

      ResultPoint bl = null;
      for (int i = 1; i < maxSize; i++) {
        bl = getBlackPointOnSegment(left, down - i, left + i, down);
        if (bl != null) {
          break;
        }
      }

      if (bl == null) {
        throw NotFoundException.getNotFoundInstance();
      }

      ResultPoint tl = null;
      for (int i = 1; i < maxSize; i++) {
        tl = getBlackPointOnSegment(left, up + i, left + i, up);
        if (tl != null) {
          break;
        }
      }

      if (tl == null) {
        throw NotFoundException.getNotFoundInstance();
      }

      ResultPoint tr = null;
      for (int i = 1; i < maxSize; i++) {
        tr = getBlackPointOnSegment(right, up + i, right - i, up);
        if (tr != null) {
          break;
        }
      }

      if (tr == null) {
        throw NotFoundException.getNotFoundInstance();
      }

      ResultPoint br = null;
      for (int i = 1; i < maxSize; i++) {
        br = getBlackPointOnSegment(right, down - i, right - i, down);
        if (br != null) {
          break;
        }
      }

      if (br == null) {
        throw NotFoundException.getNotFoundInstance();
      }

      return centerEdges(br, bl, tr, tl);

    } else {
      throw NotFoundException.getNotFoundInstance();
    }
  }

  private ResultPoint getBlackPointOnSegment(float aX, float aY, float bX, float bY) {
    int dist = MathUtils.round(MathUtils.distance(aX, aY, bX, bY));
    float xStep = (bX - aX) / dist;
    float yStep = (bY - aY) / dist;

    for (int i = 0; i < dist; i++) {
      int x = MathUtils.round(aX + i * xStep);
      int y = MathUtils.round(aY + i * yStep);
      if (image.get(x, y)) {
        return new ResultPoint(x, y);
      }
    }
    return null;
  }

  /**
   * recenters the points of a constant distance towards the center
   *
   * @param br bottom most point
   * @param bl left most point
   * @param tr right most point
   * @param tl top most point
   * @return {@link ResultPoint}[] describing the corners of the rectangular
   *         region. The first and last points are opposed on the diagonal, as
   *         are the second and third. The first point will be the topmost
   *         point and the last, the bottommost. The second point will be
   *         leftmost and the third, the rightmost
   */
  private ResultPoint[] centerEdges(ResultPoint br, ResultPoint bl,
                                    ResultPoint tr, ResultPoint tl) {

    // abonnet correction
    //       tr           tl
    //  tl                     tr
    //        br   OR    bl
    //   bl                   br
    //

    float brx = br.getX() - CORR;
    float bry = br.getY() - CORR;
    float blx = bl.getX() + CORR;
    float bly = bl.getY() - CORR;
    float trx = tr.getX() - CORR;
    float trY = tr.getY() + CORR;
    float tlx = tl.getX() + CORR;
    float tly = tl.getY() + CORR;

    if (tlx < blx) {
      return new ResultPoint[]{
          new ResultPoint(trx, trY, ResultPoint.Position.TR),
          new ResultPoint(tlx, tly, ResultPoint.Position.TL),
          new ResultPoint(brx, bry, ResultPoint.Position.BR),
          new ResultPoint(blx, bly, ResultPoint.Position.BL)};
    } else {
      return new ResultPoint[]{
          new ResultPoint(tlx, tly, ResultPoint.Position.TL),
          new ResultPoint(blx, bly, ResultPoint.Position.BL),
          new ResultPoint(trx, trY, ResultPoint.Position.TR),
          new ResultPoint(brx, bry, ResultPoint.Position.BR)};
    }
  }

  /**
   * Determines whether a segment contains a black point
   *
   * @param a          min value of the scanned coordinate
   * @param b          max value of the scanned coordinate
   * @param fixed      value of fixed coordinate
   * @param horizontal set to true if scan must be horizontal, false if vertical
   * @return true if a black point has been found, else false.
   */
  private boolean containsBlackPoint(int a, int b, int fixed, boolean horizontal) {

    if (horizontal) {
      for (int x = a; x <= b; x++) {
        if (image.get(x, fixed)) {
          return true;
        }
      }
    } else {
      for (int y = a; y <= b; y++) {
        if (image.get(fixed, y)) {
          return true;
        }
      }
    }

    return false;
  }

}
