package de.fraunhofer.aisec.cpg.meta;

import com.google.common.base.CaseFormat;
import de.fraunhofer.aisec.cpg.graph.EdgeProperty;
import de.fraunhofer.aisec.cpg.graph.Node;
import de.fraunhofer.aisec.cpg.graph.Persistable;
import de.fraunhofer.aisec.cpg.graph.edge.PropertyEdge;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.Transient;
import org.neo4j.ogm.annotation.typeconversion.Convert;
import org.neo4j.ogm.typeconversion.AttributeConverter;
import org.neo4j.ogm.typeconversion.CompositeAttributeConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReflectionUtils {

  public static boolean FAIL_ON_ERROR = false;
  private static final Logger LOGGER = LoggerFactory.getLogger(ReflectionUtils.class);
  private static final String PRIMITIVES =
      "char,byte,short,int,long,float,double,boolean,char[],byte[],short[],int[],long[],float[],double[],boolean[]";
  private static final String AUTOBOXERS =
      "java.lang.Object"
          + "java.lang.Character"
          + "java.lang.Byte"
          + "java.lang.Short"
          + "java.lang.Integer"
          + "java.lang.Long"
          + "java.lang.Float"
          + "java.lang.Double"
          + "java.lang.Boolean"
          + "java.lang.String"
          + "java.lang.Object[]"
          + "java.lang.Character[]"
          + "java.lang.Byte[]"
          + "java.lang.Short[]"
          + "java.lang.Integer[]"
          + "java.lang.Long[]"
          + "java.lang.Float[]"
          + "java.lang.Double[]"
          + "java.lang.Boolean[]"
          + "java.lang.String[]";
  private static final Map<String, Map<String, Object>> edgeProperties = new HashMap<>();
  private static Map<String, Boolean> mapsToProperty = new HashMap<>();
  private static Map<String, Boolean> mapsToRelationship = new HashMap<>();
  private static Map<String, List<Field>> fieldsIncludingSuperclasses = new HashMap<>();

  public enum Direction {
    IN,
    OUT,
    BOTH
  }

  /**
   * Get all properties of a node as a map from property name to value.
   *
   * @param n The node whose properties are to be collected
   * @return The map of all properties
   * @throws RuntimeException If {@link ReflectionUtils#FAIL_ON_ERROR} is set to true and anything
   *     goes wrong while trying to reflectively collect properties. Otherwise the errors are only
   *     logged.
   */
  public static Map<String, Object> getAllProperties(Node n) {
    HashMap<String, Object> properties = new HashMap<>();

    // Set node label (from its class)
    properties.put("label", n.getClass().getSimpleName());

    // Set node properties (from field values which are not relationships)
    List<Field> fields = getFieldsIncludingSuperclasses(n.getClass());
    for (Field f : fields) {
      if (!mapsToRelationship(f) && mapsToProperty(f)) {
        f.setAccessible(true);
        Object x = null;
        try {
          x = f.get(n);
        } catch (IllegalAccessException e) {
          String msg =
              "Failed to retrieve value for field "
                  + n.getClass().getSimpleName()
                  + "."
                  + f.getName();
          if (FAIL_ON_ERROR) {
            throw new RuntimeException(msg);
          }
          LOGGER.error(msg);
        }
        if (x == null) {
          continue;
        }
        if (hasAnnotation(f, Convert.class)) {
          try {
            properties.putAll(convertProperties(f, x));
          } catch (Exception e) {
            String msg =
                "Error converting property " + n.getClass().getSimpleName() + "." + f.getName();
            if (FAIL_ON_ERROR) {
              throw new RuntimeException(msg, e);
            }
          }
        } else if (mapsToProperty(f)) {
          properties.put(f.getName(), x);
        }
      }
    }

    return properties;
  }

  /**
   * Get all outgoing edges from a node.
   *
   * @param n The node whose outgoing edges are to be determined
   * @return Outgoing edges modeled as {@link Edge} objects
   * @throws RuntimeException If {@link ReflectionUtils#FAIL_ON_ERROR} is set to true and anything
   *     goes wrong while trying to reflectively collect outgoing edges. Otherwise the errors are
   *     only logged.
   */
  public static Set<Edge> getOutgoingEdges(Node n) {
    Set<Edge> edges = new HashSet<>();

    for (Field f : getFieldsIncludingSuperclasses(n.getClass())) {
      if (mapsToRelationship(f)) {

        if (getRelationshipDirection(f) != Direction.OUT) {
          continue;
        }
        String relName = getRelationshipLabel(f);
        Map<String, Object> edgePropertiesForField = getEdgeProperties(f);

        f.setAccessible(true);
        Object x = null;
        try {
          x = f.get(n);
        } catch (IllegalAccessException e) {
          String msg =
              "Failed to retrieve value for field "
                  + n.getClass().getSimpleName()
                  + "."
                  + f.getName();
          if (FAIL_ON_ERROR) {
            throw new RuntimeException(msg);
          }
          LOGGER.error(msg);
        }
        if (x == null) {
          continue;
        }

        // Create an edge from a field value
        if (Collection.class.isAssignableFrom(x.getClass())) {
          // Add multiple edges for collections
          for (var entry : (Collection) x) {
            if (PropertyEdge.class.isAssignableFrom(entry.getClass())) {
              Node target = ((PropertyEdge<?>) entry).getEnd();
              edges.add(new Edge(n, relName, edgePropertiesForField, target));
            } else if (Node.class.isAssignableFrom(entry.getClass())) {
              edges.add(new Edge(n, relName, edgePropertiesForField, (Node) entry));
            }
          }
        } else if (Persistable[].class.isAssignableFrom(x.getClass())) {
          for (Object entry : Collections.singletonList(x)) {
            if (getGenericStrippedType(entry.getClass())
                .getTypeName()
                .equals(PropertyEdge.class.getName())) {
              Node target = ((PropertyEdge<?>) entry).getEnd();
              edges.add(new Edge(n, relName, edgePropertiesForField, target));
            } else if (Node.class.isAssignableFrom(entry.getClass())) {
              edges.add(new Edge(n, relName, edgePropertiesForField, (Node) entry));
            }
          }
        } else {
          // Add single edge for non-collections
          if (PropertyEdge.class.isAssignableFrom(x.getClass())) {
            Node target = ((PropertyEdge<?>) x).getEnd();
            edges.add(new Edge(n, relName, edgePropertiesForField, target));
          } else if (Node.class.isAssignableFrom(x.getClass())) {
            edges.add(new Edge(n, relName, edgePropertiesForField, (Node) x));
          }
        }
      }
    }

    return edges;
  }

  private static Map<String, Object> getEdgeProperties(Field f) {
    String fieldFqn = f.getDeclaringClass().getName() + "." + f.getName();
    if (edgeProperties.containsKey(fieldFqn)) {
      return edgeProperties.get(fieldFqn);
    }

    Map<String, Object> properties =
        Arrays.stream(f.getAnnotations())
            .filter(a -> a.annotationType().getAnnotation(EdgeProperty.class) != null)
            .collect(
                Collectors.toMap(
                    a -> a.annotationType().getAnnotation(EdgeProperty.class).key(),
                    a -> {
                      try {
                        Method valueMethod = a.getClass().getDeclaredMethod("value");
                        Object value = valueMethod.invoke(a);
                        String result;
                        if (value.getClass().isArray()) {
                          String[] strings = new String[Array.getLength(value)];
                          for (int i = 0; i < Array.getLength(value); i++) {
                            strings[i] = Array.get(value, i).toString();
                          }
                          result = String.join(", ", strings);
                        } else {
                          result = value.toString();
                        }
                        return result;
                      } catch (NoSuchMethodException
                          | IllegalAccessException
                          | InvocationTargetException e) {
                        String msg =
                            "Edge property annotation "
                                + a.getClass().getName()
                                + " does not provide a 'value' method of type String";
                        if (FAIL_ON_ERROR) {
                          throw new RuntimeException(msg);
                        }
                        LOGGER.error(msg);
                        return "UNKNOWN_PROPERTY";
                      }
                    }));

    edgeProperties.put(fieldFqn, properties);
    return properties;
  }

  private static Direction getRelationshipDirection(Field f) {
    Direction direction = Direction.OUT;
    if (hasAnnotation(f, Relationship.class)) {
      Relationship rel =
          (Relationship)
              Arrays.stream(f.getAnnotations())
                  .filter(a -> a.annotationType().equals(Relationship.class))
                  .findFirst()
                  .orElse(null);

      if (rel == null) {
        String msg = "Relation direction is null, defaulting to 'OUT'";
        if (FAIL_ON_ERROR) {
          throw new RuntimeException(msg);
        }
        LOGGER.error(msg);
        return Direction.OUT;
      }

      switch (rel.direction()) {
        case Relationship.INCOMING:
          direction = Direction.IN;
          break;
        case Relationship.UNDIRECTED:
          direction = Direction.BOTH;
          break;
        default:
          direction = Direction.OUT;
      }
    }
    return direction;
  }

  private static String getRelationshipLabel(Field f) {
    String relName = f.getName();
    if (hasAnnotation(f, Relationship.class)) {
      Relationship rel =
          (Relationship)
              Arrays.stream(f.getAnnotations())
                  .filter(a -> a.annotationType().equals(Relationship.class))
                  .findFirst()
                  .orElse(null);
      return (rel == null || rel.value().trim().isEmpty()) ? f.getName() : rel.value();
    }

    return CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.UPPER_UNDERSCORE).convert(relName);
  }

  private static boolean mapsToRelationship(Field f) {
    // Using cache. This method is called from several places and does heavyweight reflection
    String key = f.getDeclaringClass().getName() + "." + f.getName();
    if (mapsToRelationship.containsKey(key)) {
      return mapsToRelationship.get(key);
    }

    boolean result =
        hasAnnotation(f, Relationship.class) || Node.class.isAssignableFrom(getContainedType(f));
    mapsToRelationship.putIfAbsent(key, result);
    return result;
  }

  private static boolean mapsToProperty(Field f) {
    // Check cache first to reduce heavy reflection
    String key = f.getDeclaringClass().getName() + "." + f.getName();
    if (mapsToProperty.containsKey(key)) {
      return mapsToProperty.get(key);
    }

    // Transient fields are not supposed to be persisted
    if (Modifier.isTransient(f.getModifiers()) || hasAnnotation(f, Transient.class)) {
      mapsToProperty.putIfAbsent(key, false);
      return false;
    }

    // constant values are not considered properties
    if (Modifier.isFinal(f.getModifiers())) {
      mapsToProperty.putIfAbsent(key, false);
      return false;
    }

    // Skip auto-generated values such as IDs
    if (hasAnnotation(f, GeneratedValue.class)) {
      mapsToProperty.putIfAbsent(key, false);
      return false;
    }

    // check if we have a converter for this
    if (f.getAnnotation(Convert.class) != null) {
      mapsToProperty.putIfAbsent(key, true);
      return true;
    }

    // check whether this is some kind of primitive datatype that seems likely to be a property
    String type = getContainedType(f).getTypeName();

    boolean result = PRIMITIVES.contains(type) || AUTOBOXERS.contains(type);
    mapsToProperty.putIfAbsent(key, result);
    return result;
  }

  private static List<Field> getFieldsIncludingSuperclasses(Class<?> c) {
    // Try cache first. There are only few (<50) different inputs c, but many calls to this method.
    if (fieldsIncludingSuperclasses.containsKey(c.getName())) {
      return fieldsIncludingSuperclasses.get(c.getName());
    }

    List<Field> fields = new ArrayList<>();
    var parent = c;
    while (parent != Object.class) {
      fields.addAll(Arrays.asList(parent.getDeclaredFields()));
      parent = parent.getSuperclass();
    }
    fieldsIncludingSuperclasses.putIfAbsent(c.getName(), fields);

    return fields;
  }

  private static Class<?> getContainedType(Field f) {
    if (Collection.class.isAssignableFrom(f.getType())) {
      // Check whether the elements in this collection are nodes
      assert f.getGenericType() instanceof ParameterizedType;
      Type[] elementTypes = ((ParameterizedType) f.getGenericType()).getActualTypeArguments();
      assert elementTypes.length == 1;
      return (Class<?>) getGenericStrippedType(elementTypes[0]);
    } else if (f.getType().isArray()) {
      return f.getType().getComponentType();
    } else {
      return f.getType();
    }
  }

  private static Type getGenericStrippedType(Type o) {
    if (o instanceof ParameterizedType) {
      return ((ParameterizedType) o).getRawType();
    } else {
      return o;
    }
  }

  private static boolean hasAnnotation(Field f, Class annotationClass) {
    return Arrays.stream(f.getAnnotations())
        .anyMatch(a -> a.annotationType().equals(annotationClass));
  }

  private static Map<String, Object> convertProperties(Field f, Object content)
      throws NoSuchMethodException, InvocationTargetException, InstantiationException,
          IllegalAccessException {
    Object converter =
        f.getAnnotation(Convert.class).value().getDeclaredConstructor().newInstance();
    if (converter instanceof AttributeConverter) {
      // Single attribute will be provided
      return Map.of(f.getName(), ((AttributeConverter) converter).toGraphProperty(content));
    } else if (converter instanceof CompositeAttributeConverter) {
      // Yields a map of properties
      return ((CompositeAttributeConverter) converter).toGraphProperties(content);
    }

    return Collections.emptyMap();
  }
}