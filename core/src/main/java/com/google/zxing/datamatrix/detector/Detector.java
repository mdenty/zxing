/*
 * Copyright 2008 ZXing authors
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

package com.google.zxing.datamatrix.detector;

import com.google.zxing.NotFoundException;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.DetectorResult;
import com.google.zxing.common.GridSampler;
import com.google.zxing.common.detector.MathUtils;
import com.google.zxing.common.detector.WhiteRectangleDetector;
import com.sun.org.apache.xpath.internal.operations.Bool;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>Encapsulates logic that can detect a Data Matrix Code in an image, even if the Data Matrix Code
 * is rotated or skewed, or partially obscured.</p>
 *
 * @author Sean Owen
 */
public final class Detector {

  public enum DatamatrixForm {
    AUTO,
    SQUARE,
    RECTANGLE
  }

  private static final boolean DO_LOG = Boolean.getBoolean("zxing.datamatrix.debug");
  private static final float DEFAULT_DIMENSION = Integer.parseInt(System.getProperty("zxing.datamatrix.transform.dimension", "50"));
  private static final float SAMPLING_CORRECTION = Float.parseFloat(System.getProperty("zxing.datamatrix.transform.correction.value", "0.2"));
  private static final float SAMPLING_CORRECTION_ON_TOP = Float.parseFloat(System.getProperty("zxing.datamatrix.transform.correction.top.value", "0.4"));
  private static final boolean CORRECT_POINTS = !Boolean.getBoolean("zxing.datamatrix.disable.correct.corner.position");
  private static final boolean USE_3_POINTS_IN_TRANSITION = !Boolean.getBoolean("zxing.datamatrix.disable.3.points.sampler");
  private static final boolean DETECT_MULTI_PIXELS_TRANSITION = !Boolean.getBoolean("zxing.datamatrix.disable.transition.correction");
  private static final DatamatrixForm FORCE_SQUARE_DATAMATRIX;

  static {
    DatamatrixForm df;
    try {
      df = DatamatrixForm.valueOf(System.getProperty("zxing.datamatrix.form"));
    } catch (Exception e) {
      log("Property zxing.datamatrix.form is invalid. Valid values are AUTO, SQUARE, RECTANGLE. AUTO was chosen.");
      df = DatamatrixForm.AUTO;
    }
    FORCE_SQUARE_DATAMATRIX = df;
  }

  private final BitMatrix image;
  private final WhiteRectangleDetector rectangleDetector;

  public Detector(BitMatrix image) throws NotFoundException {
    this.image = image;
    rectangleDetector = new WhiteRectangleDetector(image);
  }

  /**
   * <p>Detects a Data Matrix Code in an image.</p>
   *
   * @return {@link DetectorResult} encapsulating results of detecting a Data Matrix Code
   * @throws NotFoundException if no Data Matrix Code can be found
   */
  public DetectorResult detect() throws NotFoundException {

    ResultPoint[] cornerPoints = rectangleDetector.detect();
    ResultPoint pointA = cornerPoints[0];
    ResultPoint pointB = cornerPoints[1];
    ResultPoint pointC = cornerPoints[2];
    ResultPoint pointD = cornerPoints[3];

    // Point A and D are across the diagonal from one another,
    // as are B and C. Figure out which are the solid black lines
    // by counting transitions
    List<ResultPointsAndTransitions> transitions = new ArrayList<>(4);
    transitions.add(newTransitionsBetween(pointB, pointA));
    transitions.add(newTransitionsBetween(pointA, pointC));
    transitions.add(newTransitionsBetween(pointB, pointD));
    transitions.add(newTransitionsBetween(pointD, pointC));
    Collections.sort(transitions, new ResultPointsAndTransitionsComparator());

    // Sort by number of transitions. First two will be the two solid sides; last two
    // will be the two alternating black/white sides
    ResultPointsAndTransitions lSideOne = transitions.get(0);
    ResultPointsAndTransitions lSideTwo = transitions.get(1);

    // Figure out which point is their intersection by tallying up the number of times we see the
    // endpoints in the four endpoints. One will show up twice.
    Map<ResultPoint,Integer> pointCount = new HashMap<>();
    increment(pointCount, lSideOne.getFrom());
    increment(pointCount, lSideOne.getTo());
    increment(pointCount, lSideTwo.getFrom());
    increment(pointCount, lSideTwo.getTo());

    ResultPoint maybeTopLeft = null;
    ResultPoint bottomLeft = null;
    ResultPoint maybeBottomRight = null;
    for (Map.Entry<ResultPoint,Integer> entry : pointCount.entrySet()) {
      ResultPoint point = entry.getKey();
      Integer value = entry.getValue();
      if (value == 2) {
        bottomLeft = point; // this is definitely the bottom left, then -- end of two L sides
      } else {
        // Otherwise it's either top left or bottom right -- just assign the two arbitrarily now
        if (maybeTopLeft == null) {
          maybeTopLeft = point;
        } else {
          maybeBottomRight = point;
        }
      }
    }

    if (maybeTopLeft == null || bottomLeft == null || maybeBottomRight == null) {
      throw NotFoundException.getNotFoundInstance();
    }

    // Bottom left is correct but top left and bottom right might be switched
    ResultPoint[] corners = { maybeTopLeft, bottomLeft, maybeBottomRight };
    // Use the dot product trick to sort them out
    ResultPoint.orderBestPatterns(corners);

    // Now we know which is which:
    ResultPoint bottomRight = corners[0];
    bottomLeft = corners[1];
    ResultPoint topLeft = corners[2];

    // Which point didn't we find in relation to the "L" sides? that's the top right corner
    ResultPoint topRight;
    if (!pointCount.containsKey(pointA)) {
      topRight = pointA;
    } else if (!pointCount.containsKey(pointB)) {
      topRight = pointB;
    } else if (!pointCount.containsKey(pointC)) {
      topRight = pointC;
    } else {
      topRight = pointD;
    }

    // Next determine the dimension by tracing along the top or right side and counting black/white
    // transitions. Since we start inside a black module, we should see a number of transitions
    // equal to 1 less than the code dimension. Well, actually 2 less, because we are going to
    // end on a black module:

    // The top right point is actually the corner of a module, which is one of the two black modules
    // adjacent to the white module at the top right. Tracing to that corner from either the top left
    // or bottom right should work here.
    
    int dimensionTop = newTransitionsBetween(topLeft, topRight).getTransitions();
    int dimensionRight = newTransitionsBetween(bottomRight, topRight).getTransitions();
    
    if ((dimensionTop & 0x01) == 1) {
      // it can't be odd, so, round... up?
      dimensionTop++;
    }
    dimensionTop += 2;
    
    if ((dimensionRight & 0x01) == 1) {
      // it can't be odd, so, round... up?
      dimensionRight++;
    }
    dimensionRight += 2;

    BitMatrix bits;
    ResultPoint correctedTopRight;

    // Rectanguar symbols are 6x16, 6x28, 10x24, 10x32, 14x32, or 14x44. If one dimension is more
    // than twice the other, it's certainly rectangular, but to cut a bit more slack we accept it as
    // rectangular if the bigger side is at least 7/4 times the other:
    if (FORCE_SQUARE_DATAMATRIX == DatamatrixForm.RECTANGLE || (FORCE_SQUARE_DATAMATRIX == DatamatrixForm.AUTO && (4 * dimensionTop >= 7 * dimensionRight || 4 * dimensionRight >= 7 * dimensionTop))) {
      // The matrix is rectangular

      correctedTopRight =
          correctTopRightRectangular(bottomLeft, bottomRight, topLeft, topRight, dimensionTop, dimensionRight);
      if (correctedTopRight == null){
        correctedTopRight = topRight;
      }

      dimensionTop = newTransitionsBetween(topLeft, correctedTopRight).getTransitions();
      dimensionRight = newTransitionsBetween(bottomRight, correctedTopRight).getTransitions();

      if ((dimensionTop & 0x01) == 1) {
        // it can't be odd, so, round... up?
        dimensionTop++;
      }

      if ((dimensionRight & 0x01) == 1) {
        // it can't be odd, so, round... up?
        dimensionRight++;
      }

      bits = sampleGrid(image, topLeft, bottomLeft, bottomRight, correctedTopRight, dimensionTop, dimensionRight);
          
    } else {
      // The matrix is square
        
      int dimension = Math.max(dimensionRight, dimensionTop);
      // correct top right point to match the white module
      correctedTopRight = correctTopRight(bottomLeft, bottomRight, topLeft, topRight, dimension);
      if (correctedTopRight == null){
        correctedTopRight = topRight;
      }

      // Redetermine the dimension using the corrected top right point
      int dimensionCorrected = Math.max(newTransitionsBetween(topLeft, correctedTopRight, dimension).getTransitions(),
                                newTransitionsBetween(bottomRight, correctedTopRight, dimension).getTransitions());
      if ((dimensionCorrected & 0x01) == 1) {
        dimensionCorrected++;
      }

      bits = sampleGrid(image,
                        topLeft,
                        bottomLeft,
                        bottomRight,
                        correctedTopRight,
                        dimensionCorrected,
                        dimensionCorrected);
      log(bits.toString("X", "."));
    }

    return new DetectorResult(bits, new ResultPoint[]{topLeft, bottomLeft, bottomRight, correctedTopRight});
  }

  /**
   * Calculates the position of the white top right module using the output of the rectangle detector
   * for a rectangular matrix
   */
  private ResultPoint correctTopRightRectangular(ResultPoint bottomLeft,
                                                 ResultPoint bottomRight,
                                                 ResultPoint topLeft,
                                                 ResultPoint topRight,
                                                 int dimensionTop,
                                                 int dimensionRight) {

    float corr = distanceRounded(bottomLeft, bottomRight) / (float)dimensionTop;
    int norm = distanceRounded(topLeft, topRight);
    float cos = (topRight.getX() - topLeft.getX()) / norm;
    float sin = (topRight.getY() - topLeft.getY()) / norm;

    ResultPoint c1 = new ResultPoint(topRight.getX()+corr*cos, topRight.getY()+corr*sin, topRight.getPosition());

    corr = distanceRounded(bottomLeft, topLeft) / (float)dimensionRight;
    norm = distanceRounded(bottomRight, topRight);
    cos = (topRight.getX() - bottomRight.getX()) / norm;
    sin = (topRight.getY() - bottomRight.getY()) / norm;

    ResultPoint c2 = new ResultPoint(topRight.getX()+corr*cos, topRight.getY()+corr*sin, topRight.getPosition());

    if (!isValid(c1)) {
      if (isValid(c2)) {
        return c2;
      }
      return null;
    }
    if (!isValid(c2)){
      return c1;
    }

    int l1 = Math.abs(dimensionTop - newTransitionsBetween(topLeft, c1, dimensionTop).getTransitions()) +
          Math.abs(dimensionRight - newTransitionsBetween(bottomRight, c1, dimensionRight).getTransitions());
    int l2 = Math.abs(dimensionTop - newTransitionsBetween(topLeft, c2, dimensionTop).getTransitions()) +
    Math.abs(dimensionRight - newTransitionsBetween(bottomRight, c2, dimensionRight).getTransitions());

    if (l1 <= l2){
      return c1;
    }

    return c2;
  }

  /**
   * Calculates the position of the white top right module using the output of the rectangle detector
   * for a square matrix
   */
  private ResultPoint correctTopRight(ResultPoint bottomLeft,
                                      ResultPoint bottomRight,
                                      ResultPoint topLeft,
                                      ResultPoint topRight,
                                      int dimension) {

    float corr = distanceRounded(bottomLeft, bottomRight) / (float) dimension;
    int norm = distanceRounded(topLeft, topRight);
    float cos = (topRight.getX() - topLeft.getX()) / norm;
    float sin = (topRight.getY() - topLeft.getY()) / norm;

    ResultPoint c1 = new ResultPoint(topRight.getX() + corr * cos, topRight.getY() + corr * sin,
            topRight.getPosition());

    corr = distanceRounded(bottomLeft, topLeft) / (float) dimension;
    norm = distanceRounded(bottomRight, topRight);
    cos = (topRight.getX() - bottomRight.getX()) / norm;
    sin = (topRight.getY() - bottomRight.getY()) / norm;

    ResultPoint c2 = new ResultPoint(topRight.getX() + corr * cos, topRight.getY() + corr * sin,
            topRight.getPosition());

    if (!isValid(c1)) {
      if (isValid(c2)) {
        return c2;
      }
      return null;
    }
    if (!isValid(c2)) {
      return c1;
    }

    int l1 = Math.abs(newTransitionsBetween(topLeft, c1, dimension).getTransitions() -
                      newTransitionsBetween(bottomRight, c1, dimension).getTransitions());
    int l2 = Math.abs(newTransitionsBetween(topLeft, c2, dimension).getTransitions() -
                      newTransitionsBetween(bottomRight, c2, dimension).getTransitions());

    return l1 <= l2 ? c1 : c2;
  }

  private boolean isValid(ResultPoint p) {
    return p.getX() >= 0 && p.getX() < image.getWidth() && p.getY() > 0 && p.getY() < image.getHeight();
  }

  private static int distanceRounded(ResultPoint a, ResultPoint b) {
    return MathUtils.round(distance(a, b));
  }

  private static float distance(ResultPoint a, ResultPoint b) {
    return ResultPoint.distance(a, b);
  }


  /**
   * Increments the Integer associated with a key by one.
   */
  private static void increment(Map<ResultPoint,Integer> table, ResultPoint key) {
    Integer value = table.get(key);
    table.put(key, value == null ? 1 : value + 1);
  }

  private static BitMatrix sampleGrid(BitMatrix image,
                                      ResultPoint topLeft,
                                      ResultPoint bottomLeft,
                                      ResultPoint bottomRight,
                                      ResultPoint topRight,
                                      int dimensionX,
                                      int dimensionY) throws NotFoundException {

    GridSampler sampler = GridSampler.getInstance();

    return sampler.sampleGrid(image,
                              dimensionX,
                              dimensionY,
                              SAMPLING_CORRECTION,
                              SAMPLING_CORRECTION_ON_TOP,
                              dimensionX - SAMPLING_CORRECTION,
                              SAMPLING_CORRECTION_ON_TOP,
                              dimensionX - SAMPLING_CORRECTION,
                              dimensionY - SAMPLING_CORRECTION,
                              SAMPLING_CORRECTION,
                              dimensionY - SAMPLING_CORRECTION,
                              topLeft.getX(),
                              topLeft.getY(),
                              topRight.getX(),
                              topRight.getY(),
                              bottomRight.getX(),
                              bottomRight.getY(),
                              bottomLeft.getX(),
                              bottomLeft.getY());
  }

  private ResultPointsAndTransitions newTransitionsBetween(final ResultPoint from, final ResultPoint to) {
    return newTransitionsBetween(from, to, DEFAULT_DIMENSION);
  }

  private ResultPointsAndTransitions newTransitionsBetween(final ResultPoint from, final ResultPoint to, float dimension) {
    // See QR Code Detector, sizeOfBlackWhiteBlackRun()
    log("newTransitions between %s and %s", from, to);
    ResultPoint corrFrom = from, corrTo = to;
    if (shouldInverseToReorder(from, to)) {
      ResultPoint tmp = corrFrom;
      corrFrom = corrTo;
      corrTo = tmp;
    }
    if (CORRECT_POINTS) {
      corrFrom = correctResultPoint(corrFrom, corrTo, dimension);
      corrTo = correctResultPoint(corrTo, corrFrom, dimension);
    }
    int fromX = (int) (corrFrom.getX() + 0.5f);
    int fromY = (int) (corrFrom.getY() + 0.5f) ;
    int toX = (int) (corrTo.getX() + 0.5f);
    int toY = (int) (corrTo.getY() + 0.5f);
    log("corrected points %s and %s", corrFrom, corrTo);
    boolean steep = Math.abs(toY - fromY) > Math.abs(toX - fromX);
    if (steep) {
      int temp = fromX;
      fromX = fromY;
      fromY = temp;
      temp = toX;
      toX = toY;
      toY = temp;
    }

    int dx = Math.abs(toX - fromX);
    int dy = Math.abs(toY - fromY);

    int error = -dx / 2;
    int ystep = fromY < toY ? 1 : -1;
    int xstep = fromX < toX ? 1 : -1;
    int transitions = 0;
    int transitionSize = -1;
    boolean inBlack = isBlack(fromX, fromY, steep);
    int minTransitionSize = Math.max(estimateModuleSizeInPixel(from, to, dimension) / 2, 1);
    minTransitionSize = Math.min(minTransitionSize, 4);
    if (!DETECT_MULTI_PIXELS_TRANSITION) {
      minTransitionSize = 1;
    }
    for (int x = fromX, y = fromY; x != toX; x += xstep) {
      boolean isBlack = isBlack(x, y, steep);
      if (isBlack != inBlack) {
        if (transitionSize > 0) {
          transitionSize = -1;
        } else {
          transitionSize = 1;
        }
        inBlack = isBlack;
      } else if (transitionSize >= 0) {
        transitionSize++;
      }
      if (transitionSize >= minTransitionSize) {
        log("new transition %s", new ResultPoint(steep ? y : x, steep ? x : y));
        transitions++;
        transitionSize = -1;
      }
      error += dy;
      if (error > 0) {
        if (y == toY) {
          break;
        }
        y += ystep;
        error -= dx;
      }
    }
    if (transitionSize > 0 && transitionSize < minTransitionSize) {
      log("new transition at end");
      transitions++;
    }
    return new ResultPointsAndTransitions(from, to, transitions);
  }

  private boolean isBlack(int x, int y, boolean steep) {
    if (!USE_3_POINTS_IN_TRANSITION) {
      return image.get(steep ?y : x, steep ? x : y);
    }
    final int x1, x2, x3, y1, y2, y3;
    if (steep) {
      y1 = y2 = y3 = x;
      x1 = y;
      x2 = y-1;
      x3 = y+1;
    } else {
      x1 = x2 = x3 = x;
      y1 = y;
      y2 = y-1;
      y3 = y+1;
    }
    int cpt = 0;
    if (image.get(x1, y1)) {
      cpt++;
    }
    if (image.get(x2, y2)) {
      cpt++;
    }
    if (image.get(x3, y3)) {
      cpt++;
    }
    return cpt > 1;
  }

  private ResultPoint correctResultPoint(ResultPoint resultPoint, ResultPoint to, float dimension) {
    if (resultPoint.getPosition() == null) {
      return resultPoint;
    }
    float x = resultPoint.getX(), y = resultPoint.getY();
    float correction = estimateCorrection(resultPoint, to, dimension);
    log("correction %s", correction);
    switch (resultPoint.getPosition()) {
      case TL:
        return new ResultPoint(x + correction, y + correction);
      case TR:
        return new ResultPoint(x - correction, y + correction);
      case BL:
        return new ResultPoint(x + correction, y - correction);
      case BR:
      default:
        return new ResultPoint(x - correction, y - correction);
    }
  }

  private float estimateCorrection(ResultPoint resultPoint, ResultPoint to, float dimension) {
    float distance = distance(resultPoint, to);
    return distance / (dimension * 4f);
  }

  private int estimateModuleSizeInPixel(ResultPoint from, ResultPoint to, float dimension) {
    return Math.round(distance(from, to) / dimension);
  }

  private boolean shouldInverseToReorder(ResultPoint from,  ResultPoint to) {
    return from.getX() > to.getX();
  }

  /**
   * Simply encapsulates two points and a number of transitions between them.
   */
  private static final class ResultPointsAndTransitions {

    private final ResultPoint from;
    private final ResultPoint to;
    private final int transitions;

    private ResultPointsAndTransitions(ResultPoint from, ResultPoint to, int transitions) {
      this.from = from;
      this.to = to;
      this.transitions = transitions;
    }

    ResultPoint getFrom() {
      return from;
    }

    ResultPoint getTo() {
      return to;
    }

    public int getTransitions() {
      return transitions;
    }
    
    @Override
    public String toString() {
      return from + "/" + to + '/' + transitions;
    }
  }

  /**
   * Orders ResultPointsAndTransitions by number of transitions, ascending.
   */
  private static final class ResultPointsAndTransitionsComparator
      implements Comparator<ResultPointsAndTransitions>, Serializable {
    @Override
    public int compare(ResultPointsAndTransitions o1, ResultPointsAndTransitions o2) {
      return o1.getTransitions() - o2.getTransitions();
    }
  }

  private static void log(String format, Object... args) {
    if (DO_LOG) {
      System.out.println(String.format(format, args));
    }
  }

}
