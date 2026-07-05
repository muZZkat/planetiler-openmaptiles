/*
Copyright (c) 2023, MapTiler.com & OpenMapTiles contributors.
All rights reserved.

Code license: BSD 3-Clause License

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived from
  this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Design license: CC-BY 4.0

See https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md for details on usage
*/
package org.openmaptiles.layers;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.FeatureMerge;
import com.onthegomap.planetiler.ForwardingProfile;
import com.onthegomap.planetiler.VectorTile;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.expression.MultiExpression;
import com.onthegomap.planetiler.geo.GeometryException;
import com.onthegomap.planetiler.reader.SourceFeature;
import com.onthegomap.planetiler.stats.Stats;
import com.onthegomap.planetiler.util.Translations;
import com.onthegomap.planetiler.util.ZoomFunction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openmaptiles.OpenMapTilesProfile;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.openmaptiles.generated.Tables;

/**
 * Defines the logic for generating map elements for natural land cover polygons like ice, sand, and forest in the
 * {@code landcover} layer from source features.
 * <p>
 * This class is ported to Java from
 * <a href="https://github.com/openmaptiles/openmaptiles/tree/master/layers/landcover">OpenMapTiles landcover sql
 * files</a>.
 */
public class Landcover implements
  OpenMapTilesSchema.Landcover,
  OpenMapTilesProfile.NaturalEarthProcessor,
  Tables.OsmLandcoverPolygon.Handler,
  ForwardingProfile.LayerPostProcessor {

  /*
   * Large ice areas come from natural earth and the rest come from OpenStreetMap at higher zoom
   * levels. At render-time, postProcess() merges polygons into larger connected area based
   * on the number of points in the original area.  Since postProcess() only has visibility into
   * features on a single tile, process() needs to pass the number of points the original feature
   * had through using a temporary "_numpoints" attribute.
   */

  // forge-overland: relaxed from stock (z<=9: 2px, z10: 4px, z11-13: 8px), which held
  // small woods back until z12-13 (~600m minimum footprint through z11). Now: ~150m
  // patches arrive by z11 instead of z13, and the new z5-6 range only keeps forests
  // bigger than ~5-10km — big enough to matter on a continental view.
  public static final ZoomFunction<Number> MIN_PIXEL_SIZE_THRESHOLDS = ZoomFunction.fromMaxZoomThresholds(Map.of(
    13, 4,
    11, 2
  ));
  private static final String TEMP_NUM_POINTS_ATTR = "_numpoints";
  private static final Set<String> WOOD_OR_FOREST = Set.of(
    FieldValues.SUBCLASS_WOOD,
    FieldValues.SUBCLASS_FOREST
  );
  private final MultiExpression.Index<String> classMapping;

  public Landcover(Translations translations, PlanetilerConfig config, Stats stats) {
    this.classMapping = FieldMappings.Class.index();
  }

  private String getClassFromSubclass(String subclass) {
    return subclass == null ? null : classMapping.getOrElse(Map.of(Fields.SUBCLASS, subclass), null);
  }

  @Override
  public void processNaturalEarth(String table, SourceFeature feature,
    FeatureCollector features) {
    record LandcoverInfo(String subclass, int minzoom, int maxzoom) {}
    LandcoverInfo info = switch (table) {
      case "ne_110m_glaciated_areas" -> new LandcoverInfo(FieldValues.SUBCLASS_GLACIER, 0, 1);
      case "ne_50m_glaciated_areas" -> new LandcoverInfo(FieldValues.SUBCLASS_GLACIER, 2, 4);
      case "ne_10m_glaciated_areas" -> new LandcoverInfo(FieldValues.SUBCLASS_GLACIER, 5, 6);
      case "ne_50m_antarctic_ice_shelves_polys" -> new LandcoverInfo("ice_shelf", 2, 4);
      case "ne_10m_antarctic_ice_shelves_polys" -> new LandcoverInfo("ice_shelf", 5, 6);
      default -> null;
    };
    if (info != null) {
      String clazz = getClassFromSubclass(info.subclass);
      if (clazz != null) {
        features.polygon(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
          .setAttr(Fields.CLASS, clazz)
          .setAttr(Fields.SUBCLASS, info.subclass)
          .setZoomRange(info.minzoom, info.maxzoom);
      }
    }
  }

  @Override
  public void process(Tables.OsmLandcoverPolygon element, FeatureCollector features) {
    String subclass = element.subclass();
    String clazz = getClassFromSubclass(subclass);
    if (clazz != null) {
      features.polygon(LAYER_NAME).setBufferPixels(BUFFER_SIZE)
        .setMinPixelSizeOverrides(MIN_PIXEL_SIZE_THRESHOLDS)
        // default is 0.1, this helps reduce size of some heavy z7-10 tiles
        .setPixelToleranceBelowZoom(10, 0.25)
        .setAttr(Fields.CLASS, clazz)
        .setAttr(Fields.SUBCLASS, subclass)
        .setNumPointsAttr(TEMP_NUM_POINTS_ATTR)
        // forge-overland: stock z7 floor meant NO forests at all below z7 (below that,
        // landcover is Natural Earth glaciers only — Australia goes bald). z5 with the
        // coarse min-pixel cull keeps the continent's green structure when zoomed out.
        .setMinZoom(5);

      // forge-overland: promote Australian state forests into the park layer. Most are
      // mapped as plain landuse=forest (+ name) — anonymous wood here, never reaching
      // Park.java — yet on an AU paper map the state-forest estate is first-class,
      // coloured distinctly from national parks. Emit a park polygon (class=state_forest)
      // and a gridded label point alongside the normal landcover wood.
      String name = element.source().getString("name");
      if (Park.isStateForest(element.source().getString("protection_title"), name)) {
        features.polygon(OpenMapTilesSchema.Park.LAYER_NAME).setBufferPixels(4)
          .setAttr("class", "state_forest")
          .setAttrWithMinzoom("name", name, 5)
          .setMinPixelSize(1)
          .setMinZoom(1);
        if (name != null) {
          try {
            double area = element.source().area();
            int labelMinzoom = (int) Math.clamp(
              Math.floor(20 - Math.log(area / Park.WORLD_AREA_FOR_70K_SQUARE_METERS) / Park.LOG2), 6, 14);
            features.pointOnSurface(OpenMapTilesSchema.Park.LAYER_NAME).setBufferPixels(256)
              .setAttr("class", "state_forest")
              .setAttr("name", name)
              .setPointLabelGridPixelSize(14, 100)
              .setMinZoom(labelMinzoom);
          } catch (GeometryException e) {
            // no label point for an un-measurable geometry; the fill still draws
          }
        }
      }
    }
  }

  @Override
  public List<VectorTile.Feature> postProcess(int zoom, List<VectorTile.Feature> items) throws GeometryException {
    if (zoom < 7 || zoom > 13) {
      for (var item : items) {
        item.tags().remove(TEMP_NUM_POINTS_ATTR);
      }
      return items;
    } else { // z7-13
      // merging only merges polygons with the same attributes, so use this temporary key
      // to separate features into layers that will be merged separately
      String tempGroupKey = "_group";
      List<VectorTile.Feature> result = new ArrayList<>();
      List<VectorTile.Feature> toMerge = new ArrayList<>();
      for (var item : items) {
        Map<String, Object> attrs = item.attrs();
        Object numPointsObj = attrs.remove(TEMP_NUM_POINTS_ATTR);
        Object subclassObj = attrs.get(Fields.SUBCLASS);
        if (numPointsObj instanceof Number num && subclassObj instanceof String subclass) {
          long numPoints = num.longValue();
          if (zoom >= 10) {
            if (WOOD_OR_FOREST.contains(subclass) && numPoints < 300) {
              attrs.put(tempGroupKey, "<300");
              toMerge.add(item);
            } else { // don't merge
              result.add(item);
            }
          } else if (zoom >= 8 && zoom <= 9) {
            if (WOOD_OR_FOREST.contains(subclass)) {
              attrs.put(tempGroupKey, numPoints < 300 ? "<300" : ">300");
              toMerge.add(item);
            } else { // don't merge
              result.add(item);
            }
          } else { // zoom 7
            toMerge.add(item);
          }
        } else {
          result.add(item);
        }
      }
      var merged = FeatureMerge.mergeOverlappingPolygons(toMerge, 4);
      for (var item : merged) {
        item.tags().remove(tempGroupKey);
      }
      result.addAll(merged);
      return result;
    }
  }
}
