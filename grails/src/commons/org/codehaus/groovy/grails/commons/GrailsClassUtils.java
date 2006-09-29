/* Copyright 2004-2005 the original author or authors.
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
package org.codehaus.groovy.grails.commons;


import groovy.lang.Closure;
import groovy.lang.GroovyObject;
import groovy.lang.MetaClass;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.InvalidPropertyException;

import java.beans.PropertyDescriptor;
import java.util.*;
import java.lang.reflect.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * @author Graeme Rocher
 * @since 08-Jul-2005
 * 
 * Class containing utility methods for dealing with Grails class artifacts
 * 
 */
public class GrailsClassUtils {

    public static final Map PRIMITIVE_TYPE_COMPATIBLE_CLASSES = new HashMap();

    /**
     * Just add two entries to the class compatibility map
     * @param left
     * @param right
     */
    private static final void registerPrimitiveClassPair(Class left, Class right)
    {
        PRIMITIVE_TYPE_COMPATIBLE_CLASSES.put( left, right);
        PRIMITIVE_TYPE_COMPATIBLE_CLASSES.put( right, left);
    }

    static
    {
        registerPrimitiveClassPair( Boolean.class, boolean.class);
        registerPrimitiveClassPair( Integer.class, int.class);
        registerPrimitiveClassPair( Short.class, short.class);
        registerPrimitiveClassPair( Byte.class, byte.class);
        registerPrimitiveClassPair( Character.class, char.class);
        registerPrimitiveClassPair( Long.class, long.class);
        registerPrimitiveClassPair( Float.class, float.class);
        registerPrimitiveClassPair( Double.class, double.class);
    }

    /**
     * Returns true of the specified Groovy class is a bootstrap
     * @param clazz The class to check
     * @return True if the class is a bootstrap class
     */
    public static boolean isBootstrapClass( Class clazz ) {
        return clazz.getName().endsWith(DefaultGrailsBootstrapClass.BOOT_STRAP)  && !Closure.class.isAssignableFrom(clazz);
    }

    /**
     * Returns true of the specified Groovy class is a taglib
     * @param clazz
     * @return True if the class is a taglib
     */
    public static boolean isTagLibClass( Class clazz ) {
        return isTagLibClass(clazz.getName())  && !Closure.class.isAssignableFrom(clazz);
    }

    public static boolean isTagLibClass(String className) {
        return className.endsWith( DefaultGrailsTagLibClass.TAG_LIB );
    }
    /**
     * Returns true of the specified Groovy class is a controller
     * @param clazz The class to check
     * @return True if the class is a controller
     */
    public static boolean isControllerClass( Class clazz ) {
        return isControllerClass(clazz.getName())  && !Closure.class.isAssignableFrom(clazz);
    }

    public static boolean isControllerClass(String className) {
        return className.endsWith(DefaultGrailsControllerClass.CONTROLLER);
    }

    /**
     * <p>Returns true if the specified class is a page flow class type</p>
     *
     * @param clazz the class to check
     * @return  True if the class is a page flow class
     */
    public static boolean isPageFlowClass( Class clazz ) {
        return isPageFlowClass(clazz.getName())  && !Closure.class.isAssignableFrom(clazz);
    }

    public static boolean isPageFlowClass(String className) {
        return className.endsWith(DefaultGrailsPageFlowClass.PAGE_FLOW);
    }

    /**
     * <p>Returns true if the specified class is a data source.
     *
     * @param clazz The class to check
     * @return True if the class is a data source
     */
    public static boolean isDataSource(Class clazz) {
        return clazz.getName().endsWith(DefaultGrailsDataSource.DATA_SOURCE) && !Closure.class.isAssignableFrom(clazz);
    }

    /**
     * <p>Returns true if the specified class is a service.
     *
     * @param clazz The class to check
     * @return True if the class is a service class
     */
    public static boolean isService(Class clazz) {
        return isService(clazz.getName()) && !Closure.class.isAssignableFrom(clazz);
    }
    public static boolean isService(String className) {
        return className.endsWith(DefaultGrailsServiceClass.SERVICE);
    }

    /**
     * <p>Returns true if the specified class is a domain class. In Grails a domain class
     * is any class that has "id" and "version" properties</p>
     *
     * @param clazz The class to check
     * @return A boolean value
     */
    public static boolean isDomainClass( Class clazz ) {
        // its not a closure
        if(clazz == null)return false;
        if(Closure.class.isAssignableFrom(clazz)) {
            return false;
        }
        Class testClass = clazz;
        boolean result = false;
        while(testClass!=null&&!testClass.equals(GroovyObject.class)&&!testClass.equals(Object.class)) {
            try {
                // make sure the identify and version field exist
                testClass.getDeclaredField( GrailsDomainClassProperty.IDENTITY );
                testClass.getDeclaredField( GrailsDomainClassProperty.VERSION );

                // passes all conditions return true
                result = true;
                break;
            } catch (SecurityException e) {
                // ignore
            } catch (NoSuchFieldException e) {
                // ignore
            }
            testClass = testClass.getSuperclass();
        }
        return result;
    }

    public static boolean isTaskClass(Class clazz) {
        try {
            clazz.getDeclaredMethod( GrailsTaskClassProperty.EXECUTE , new Class[]{});
        } catch (SecurityException e) {
            return false;
        } catch (NoSuchMethodException e) {
            return false;
        }
        return isTaskClass(clazz.getName());
    }

    public static boolean isTaskClass(String className) {
        return className.endsWith(DefaultGrailsTaskClass.JOB);
    }

    /**
     *
     * Returns true if the specified property in the specified class is of the specified type
     *
     * @param clazz The class which contains the property
     * @param propertyName The property name
     * @param type The type to check
     *
     * @return A boolean value
     */
    public static boolean isPropertyOfType( Class clazz, String propertyName, Class type ) {
        try {

            Class propType = getProperyType( clazz, propertyName );
            return propType != null && propType.equals(type);
        }
        catch(Exception e) {
            return false;
        }
    }


    /**
     * Returns the value of the specified property and type from an instance of the specified Grails class
     *
     * @param clazz The name of the class which contains the property
     * @param propertyName The property name
     * @param propertyType The property type
     *
     * @return The value of the property or null if none exists
     */
    public static Object getPropertyValueOfNewInstance(Class clazz, String propertyName, Class propertyType) {
        // validate
        if(clazz == null || StringUtils.isBlank(propertyName))
            return null;

        try {
            BeanWrapper wrapper = new BeanWrapperImpl(clazz.newInstance());
            Object pValue = wrapper.getPropertyValue( propertyName );
            if(pValue == null)
                return null;

            if(propertyType.isAssignableFrom(pValue.getClass())) {
                return pValue;
            }
            else {
                return null;
            }

        } catch (Exception e) {
            // if there are any errors in instantiating just return null
            return null;
        }
    }


    /**
     * Retrieves a PropertyDescriptor for the specified instance and property value
     *
     * @param instance The instance
     * @param propertyValue The value of the property
     * @return The PropertyDescriptor
     */
    public static PropertyDescriptor getPropertyDescriptorForValue(Object instance, Object propertyValue) {
        if(instance == null || propertyValue == null)
            return null;

        BeanWrapper wrapper = new BeanWrapperImpl(instance);
        PropertyDescriptor[] descriptors = wrapper.getPropertyDescriptors();

        for (int i = 0; i < descriptors.length; i++) {
            Object value = wrapper.getPropertyValue( descriptors[i].getName() );
            if(propertyValue.equals(value))
                return descriptors[i];
        }
        return null;
    }
    /**
     * Returns the type of the given property contained within the specified class
     *
     * @param clazz The class which contains the property
     * @param propertyName The name of the property
     *
     * @return The property type or null if none exists
     */
    public static Class getProperyType(Class clazz, String propertyName) {
        if(clazz == null || StringUtils.isBlank(propertyName))
            return null;

        try {
            BeanWrapper wrapper = new BeanWrapperImpl(clazz.newInstance());
            return wrapper.getPropertyType(propertyName);

        } catch (Exception e) {
            // if there are any errors in instantiating just return null for the moment
            return null;
        }
    }

    /**
     * Retrieves all the properties of the given class for the given type
     *
     * @param clazz The class to retrieve the properties from
     * @param propertyType The type of the properties you wish to retrieve
     *
     * @return An array of PropertyDescriptor instances
     */
    public static PropertyDescriptor[] getPropertiesOfType(Class clazz, Class propertyType) {
        if(clazz == null || propertyType == null)
            return new PropertyDescriptor[0];

        Set properties = new HashSet();
        try {
            BeanWrapper wrapper = new BeanWrapperImpl(clazz.newInstance());
            PropertyDescriptor[] descriptors = wrapper.getPropertyDescriptors();

            for (int i = 0; i < descriptors.length; i++) {
                if(descriptors[i].getPropertyType().equals( propertyType )  ) {
                    properties.add(descriptors[i]);
                }
            }

        } catch (Exception e) {
            // if there are any errors in instantiating just return null for the moment
            return new PropertyDescriptor[0];
        }
        return (PropertyDescriptor[])properties.toArray( new PropertyDescriptor[ properties.size() ] );
    }

    /**
     * Retrieves a property of the given class of the specified name and type
     * @param clazz The class to retrieve the property from
     * @param propertyName The name of the property
     * @param propertyType The type of the property
     *
     * @return A PropertyDescriptor instance or null if none exists
     */
    public static PropertyDescriptor getProperty(Class clazz, String propertyName, Class propertyType) {
        if(clazz == null || propertyName == null || propertyType == null)
            return null;

        try {
            BeanWrapper wrapper = new BeanWrapperImpl(clazz.newInstance());
            PropertyDescriptor pd = wrapper.getPropertyDescriptor(propertyName);
            if(pd.getPropertyType().equals( propertyType )) {
                return pd;
            }
            else {
                return null;
            }
        } catch (Exception e) {
            // if there are any errors in instantiating just return null for the moment
            return null;
        }
    }

    /**
     * Returns the class name without the package prefix
     *
     * @param targetClass The class to get a short name for
     * @return The short name of the class
     */
    public static String getShortName(Class targetClass) {
        String className = targetClass.getName();
        int i = className.lastIndexOf(".");
        if(i > -1) {
            className = className.substring( i + 1, className.length() );
        }
        return className;
    }

    /**
     * Returns the property name equivalent for the specified class
     *
     * @param targetClass The class to get the property name for
     * @return A property name reperesentation of the class name (eg. MyClass becomes myClass)
     */
    public static String getPropertyNameRepresentation(Class targetClass) {
        String shortName = getShortName(targetClass);
        return getPropertyNameRepresentation(shortName);
    }

    /**
     * Returns the property name representation of the given name
     *
     * @param name The name to convert
     * @return The property name representation
     */
    public static String getPropertyNameRepresentation(String name) {
        String propertyName = name.substring(0,1).toLowerCase(Locale.ENGLISH) + name.substring(1);
        if(propertyName.indexOf(' ') > -1) {
            propertyName = propertyName.replaceAll("\\s", "");
        }
        return propertyName;
    }

    /**
     * Converts a property name into its natural language equivalent eg ('firstName' becomes 'First Name')
     * @param name The property name to convert
     * @return The converted property name
     */
    public static String getNaturalName(String name) {
        List words = new ArrayList();
        int i = 0;
        char[] chars = name.toCharArray();
        for (int j = 0; j < chars.length; j++) {
            char c = chars[j];
            String w;
            if(i >= words.size()) {
                w = "";
                words.add(i, w);
            }
            else {
                w = (String)words.get(i);
            }

            if(Character.isLowerCase(c) || Character.isDigit(c)) {
                if(Character.isLowerCase(c) && w.length() == 0)
                    c = Character.toUpperCase(c);
                else if(w.length() > 1 && Character.isUpperCase(w.charAt(w.length() - 1)) ) {
                    w = "";
                    words.add(++i,w);
                }

                words.set(i, w + c);
            }
            else if(Character.isUpperCase(c)) {
                if((i == 0 && w.length() == 0) || Character.isUpperCase(w.charAt(w.length() - 1)) ) 	{
                    words.set(i, w + c);
                }
                else {
                    words.add(++i, String.valueOf(c));
                }
            }

        }

        StringBuffer buf = new StringBuffer();

        for (Iterator j = words.iterator(); j.hasNext();) {
            String word = (String) j.next();
            buf.append(word);
            if(j.hasNext())
                buf.append(' ');
        }
        return buf.toString();
    }


    /**
     * Convenience method for converting a collection to an Object[]
     * @param c The collection
     * @return  An object array
     */
    public static Object[] collectionToObjectArray(Collection c) {
        if(c == null) return new Object[0];

        return c.toArray(new Object[c.size()]);
    }


    /**
     * Detect if left and right types are matching types. In particular,
     * test if one is a primitive type and the other is the corresponding
     * Java wrapper type. Primitive and wrapper classes may be passed to
     * either arguments.
     *
     * @param leftType
     * @param rightType
     * @return true if one of the classes is a native type and the other the object representation
     * of the same native type
     */
    public static boolean isMatchBetweenPrimativeAndWrapperTypes(Class leftType, Class rightType) {
        if (leftType == null) {
            throw new NullPointerException("Left type is null!");
        } else if (rightType == null) {
            throw new NullPointerException("Right type is null!");
        } else {
            Class r = (Class)PRIMITIVE_TYPE_COMPATIBLE_CLASSES.get(leftType);
            return r == rightType;
        }
    }

    /**
     * <p>Tests whether or not the left hand type is compatible with the right hand type in Groovy
     * terms, i.e. can the left type be assigned a value of the right hand type in Groovy.</p>
     * <p>This handles Java primitive type equivalence and uses isAssignableFrom for all other types,
     * with a bit of magic for native types and polymorphism i.e. Number assigned an int.
     * If either parameter is null an exception is thrown</p>
     *
     * @param leftType The type of the left hand part of a notional assignment
     * @param rightType The type of the right hand part of a notional assignment
     * @return True if values of the right hand type can be assigned in Groovy to variables of the left hand type.
     */
    public static boolean isGroovyAssignableFrom(
            Class leftType, Class rightType)
    {
        if (leftType == null) {
            throw new NullPointerException("Left type is null!");
        } else if (rightType == null) {
            throw new NullPointerException("Right type is null!");
        } else if (leftType == Object.class) {
            return true;
        } else if (leftType == rightType) {
            return true;
        } else {
            // check for primitive type equivalence
            Class r = (Class)PRIMITIVE_TYPE_COMPATIBLE_CLASSES.get(leftType);
            boolean result = r == rightType;

            if (!result)
            {
                // If no primitive <-> wrapper match, it may still be assignable
                // from polymorphic primitives i.e. Number -> int (AKA Integer)
                if (rightType.isPrimitive())
                {
                    // see if incompatible
                    r = (Class)PRIMITIVE_TYPE_COMPATIBLE_CLASSES.get(rightType);
                    if (r != null)
                    {
                        result = leftType.isAssignableFrom(r);
                    }
                } else
                {
                    // Otherwise it may just be assignable using normal Java polymorphism
                    result = leftType.isAssignableFrom(rightType);
                }
            }
            return result;
        }
    }

    /**
     * <p>Work out if the specified property is readable and static. Java introspection does not
     * recognize this concept of static properties but Groovy does. We also consider public static fields
     * as static properties with no getters/setters</p>
     *
     * @param clazz The class to check for static property
     * @param propertyName The property name
     * @return true if the property with name propertyName has a static getter method
     */
    public static boolean isStaticProperty( Class clazz, String propertyName)
    {
        Method getter = BeanUtils.findDeclaredMethod(clazz, getGetterName(propertyName), null);
        if (getter != null)
        {
            return isPublicStatic(getter);
        }
        else
        {
            try
            {
                Field f = clazz.getDeclaredField(propertyName);
                if (f != null)
                {
                    return isPublicStatic(f);
                }
            }
            catch (NoSuchFieldException e)
            {
            }
        }

        return false;
    }

    /**
     * Determine whether the method is declared public static
     * @param m
     * @return True if the method is declared public static
     */
    public static boolean isPublicStatic( Method m)
    {
        final int modifiers = m.getModifiers();
        return Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers);
    }

    /**
     * Determine whether the field is declared public static
     * @param f
     * @return True if the field is declared public static
     */
    public static boolean isPublicStatic( Field f)
    {
        final int modifiers = f.getModifiers();
        return Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers);
    }

    /**
     * Calculate the name for a getter method to retrieve the specified property
     * @param propertyName
     * @return The name for the getter method for this property, if it were to exist, i.e. getConstraints
     */
    public static String getGetterName(String propertyName)
    {
        return "get" + Character.toUpperCase(propertyName.charAt(0))
            + propertyName.substring(1);
    }

    /**
     * <p>Get a static property value, which has a public static getter or is just a public static field.</p>
     *
     * @param clazz The class to check for static property
     * @param name The property name
     * @return The value if there is one, or null if unset OR there is no such property
     */
    public static Object getStaticPropertyValue(Class clazz, String name)
    {
        Method getter = BeanUtils.findDeclaredMethod(clazz, getGetterName(name), null);
        try
        {
            if (getter != null)
            {
                return getter.invoke(null, null);
            }
            else
            {
                Field f = clazz.getDeclaredField(name);
                if (f != null)
                {
                    return f.get(null);
                }
            }
        }
        catch (Exception e)
        {
        }
        return null;
    }

    /**
     * <p>Looks for a property of the reference instance with a given name.</p>
     * <p>If found its value is returned. We follow the Java bean conventions with augmentation for groovy support
     * and static fields/properties. We will therefore match, in this order:
     * </p>
     * <ol>
     * <li>Standard public bean property (with getter or just public field, using normal introspection)
     * <li>Public static property with getter method
     * <li>Public static field
     * </ol>
     *
     * @return property value
     * @throws BeansException if no such property found
     */
    public static Object getPropertyOrStaticPropertyOrFieldValue(Object obj, String name) throws BeansException
    {
        BeanWrapper ref = new BeanWrapperImpl(obj);
        if (ref.isReadableProperty(name)) {
            return ref.getPropertyValue(name);
        }
        else
        {
            // Look for public fields
            if (isPublicField(obj, name))
            {
                return getFieldValue(obj, name);
            }

            // Look for statics
            Class clazz = obj.getClass();
            if (isStaticProperty(clazz, name))
            {
                return getStaticPropertyValue(clazz, name);
            }
            else
            {
                throw new InvalidPropertyException(clazz, name, "No property or static property or static field");
            }
        }
    }

    /**
     * Get the value of a declared field on an object
     *
     * @param obj
     * @param name
     * @return The object value or null if there is no such field or access problems
     */
    public static Object getFieldValue(Object obj, String name)
    {
        Class clazz = obj.getClass();
        Field f = null;
        try
        {
            f = clazz.getDeclaredField(name);
            return f.get(obj);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * Work out if the specified object has a public field with the name supplied.
     *
     * @param obj
     * @param name
     * @return True if a public field with the name exists
     */
    public static boolean isPublicField(Object obj, String name)
    {
        Class clazz = obj.getClass();
        Field f = null;
        try
        {
            f = clazz.getDeclaredField(name);
            return Modifier.isPublic(f.getModifiers());
        }
        catch (NoSuchFieldException e)
        {
            return false;
        }
    }
}
