package org.mapfish.print.map.tiled;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.mapfish.print.map.AbstractLayerParams;
import org.mapfish.print.parser.HasDefaultValue;
import org.mapfish.print.wrapper.PObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Contains the standard parameters for tiled layers. */
public abstract class AbstractTiledLayerParams extends AbstractLayerParams {

  private static final Logger LOGGER = LoggerFactory.getLogger(AbstractTiledLayerParams.class);

  /**
   * The name of the style (in Configuration or Template) to use when drawing the layer to the map.
   * This is separate from the style in that it indicates how to draw the map. It allows one to
   * apply any of the SLD raster styling.
   */
  @HasDefaultValue public String rasterStyle = "raster";

  /**
   * Custom parameters to use when making vector tiles.
   *
   * <p>The json should look something like:
   *
   * <pre><code>
   * {
   *     "param1Name": "value",
   *     "param2Name": "value1"
   * }
   * </code></pre>
   */
  @HasDefaultValue public PObject vectorTileParams = null;

  /**
   * URL of vector styles.
   * @deprecated Moved into vectorTileParams
   */
  @HasDefaultValue public String vectorStyles = null;

  /** 
   * Read the vectorTileParams into a Multimap.
   * @return  the multimap 
   */
  public Map<String, String> getVectorTileParams() {
    HashMap<String, String> params = new HashMap<>();
    if(vectorStyles != null) {
      params.put("vectorStyles", vectorStyles);
    }
    if (vectorTileParams != null) {
      for (Iterator iterator = vectorTileParams.keys(); iterator.hasNext();) {
        String key = (String) iterator.next();
        params.put(key, vectorTileParams.getString(key));
      }
    }
    return params;
  }
  
  /** Constructor. */
  protected AbstractTiledLayerParams() {
    super();
  }

  /**
   * Copy constructor.
   *
   * @param other the object to copy
   */
  protected AbstractTiledLayerParams(final AbstractTiledLayerParams other) {
    super(other);
    this.rasterStyle = other.rasterStyle;
  }

  /**
   * Get the base url for all tile requests. For example it might be 'http://server
   * .com/geoserver/gwc/service/wmts'.
   */
  public abstract String getBaseUrl();

  /**
   * Validates the provided base url.
   *
   * @return True, if the url is valid.
   */
  public abstract boolean validateBaseUrl();

  /**
   * Create a URL that is common to all image requests for this layer. It will take the base url and
   * append all mergeable and custom params to the base url.
   */
  public abstract String createCommonUrl() throws URISyntaxException;

  public CoordinateReferenceSystem getCRS() {
    return null;
  }
  
}
