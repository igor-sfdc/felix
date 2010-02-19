/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.webconsole.internal.misc;


import java.io.*;
import java.net.URL;
import java.text.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.felix.webconsole.*;
import org.apache.felix.webconsole.internal.OsgiManagerPlugin;
import org.apache.felix.webconsole.internal.Util;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;


/**
 * ConfigurationRender plugin renders the configuration status - a textual
 * representation of the current framework status. The content itself is
 *  internally generated by the {@link ConfigurationPrinter} plugins.
 */
public class ConfigurationRender extends SimpleWebConsolePlugin implements OsgiManagerPlugin
{

    private static final String LABEL = "config";
    private static final String TITLE = "Configuration Status";
    private static final String[] CSS_REFS = null;

    private static final String TAB_PROPS = "System properties";
    private static final String TAB_THREADS = "Threads";

    /**
     * Formatter pattern to generate a relative path for the generation
     * of the plain text or zip file representation of the status. The file
     * name consists of a base name and the current time of status generation.
     */
    private static final SimpleDateFormat FILE_NAME_FORMAT = new SimpleDateFormat( "'" + LABEL
        + "/configuration-status-'yyyyMMdd'-'HHmmZ" );

    /**
     * Formatter pattern to render the current time of status generation.
     */
    private static final DateFormat DISPLAY_DATE_FORMAT = DateFormat.getDateTimeInstance( DateFormat.LONG,
        DateFormat.LONG, Locale.US );

    private ServiceTracker cfgPrinterTracker;

    private int cfgPrinterTrackerCount;

    private SortedMap configurationPrinters = new TreeMap();

    /** Default constructor */
    public ConfigurationRender()
    {
        super(LABEL, TITLE, CSS_REFS);
    }


    /**
     * @see org.apache.felix.webconsole.AbstractWebConsolePlugin#doGet(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    protected final void doGet( HttpServletRequest request, HttpServletResponse response ) throws ServletException,
        IOException
    {
        if ( request.getPathInfo().endsWith( ".txt" ) )
        {
            response.setContentType( "text/plain; charset=utf-8" );
            ConfigurationWriter pw = new PlainTextConfigurationWriter( response.getWriter() );
            printConfigurationStatus( pw, ConfigurationPrinter.MODE_TXT );
            pw.flush();
        }
        else if ( request.getPathInfo().endsWith( ".zip" ) )
        {
            String type = getServletContext().getMimeType( request.getPathInfo() );
            if ( type == null )
            {
                type = "application/x-zip";
            }
            response.setContentType( type );

            ZipOutputStream zip = new ZipOutputStream( response.getOutputStream() );
            zip.setLevel( 9 );
            zip.setMethod( ZipOutputStream.DEFLATED );

            final ConfigurationWriter pw = new ZipConfigurationWriter( zip );
            printConfigurationStatus( pw, ConfigurationPrinter.MODE_ZIP );
            pw.flush();

            addAttachments( pw, ConfigurationPrinter.MODE_ZIP );
            zip.finish();
        }
        else if ( request.getPathInfo().endsWith( ".nfo" ) )
        {
            response.setContentType( "text/html; charset=utf-8" );
            // disable cache
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, max-age=0");
            response.addHeader("Expires", "Mon, 2 Sun 2001 05:00:00 GMT");
            response.addHeader("Pragma", "no-cache");

            String name = request.getPathInfo();
            name = name.substring( name.lastIndexOf('/') + 1);
            name = name.substring(0, name.length() - 4);

            ConfigurationWriter pw = new HtmlConfigurationWriter( response.getWriter() );
            pw.println ( "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\"" );
            pw.println ( "  \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">" );
            pw.println ( "<html xmlns=\"http://www.w3.org/1999/xhtml\">" );
            pw.println ( "<head><title>dummy</title></head><body><div>" );

            if ( TAB_PROPS.equals( name ) )
            {
                printSystemProperties( pw );
                pw.println( "</div></body></html>" );
                return;
            }
            else if ( TAB_THREADS.equals( name))
            {
                printThreads( pw );
                pw.println( "</div></body></html>" );
                return;
            }
            else
            {
                Collection printers = getConfigurationPrinters();
                for (Iterator i = printers.iterator(); i.hasNext();)
                {
                    final PrinterDesc desc = (PrinterDesc) i.next();
                    if (desc.printer.getTitle().equals( name ) )
                    {
                        printConfigurationPrinter( pw, desc.printer, ConfigurationPrinter.MODE_WEB );
                        pw.println( "</div></body></html>" );
                        return;
                    }
                }
            }

            response.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Invalid configuration printer: " + name);
        }
        else
        {
            super.doGet( request, response );
        }
    }


    /**
     * @see org.apache.felix.webconsole.AbstractWebConsolePlugin#renderContent(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    protected final void renderContent( HttpServletRequest request, HttpServletResponse response ) throws IOException
    {

        //ConfigurationWriter pw = new HtmlConfigurationWriter( response.getWriter() );
        PrintWriter pw = response.getWriter();

        Util.startScript(pw);
        pw.println("$(document).ready(function() {$('#tabs').tabs()} );");
        Util.endScript(pw);

        pw.println("<br/><p class=\"statline\">");

        final Date currentTime = new Date();
        synchronized ( DISPLAY_DATE_FORMAT )
        {
            pw.print("Date: ");
            pw.println(DISPLAY_DATE_FORMAT.format(currentTime));
        }

        synchronized ( FILE_NAME_FORMAT )
        {
            String fileName = FILE_NAME_FORMAT.format( currentTime );
            pw.print("<br/>Download as <a href='");
            pw.print(fileName);
            pw.print(".txt'>[Single File]</a> or as <a href='");
            pw.print(fileName);
            pw.println(".zip'>[ZIP]</a>");
        }

        pw.println("</p>"); // status line

        // display some information while the data is loading
        // load the data (hidden to begin with)
        pw.println("<div id='tabs'> <!-- tabs container -->");
        pw.println("<ul> <!-- tabs on top -->");

        // print headers only
        final String pluginRoot = request.getAttribute( WebConsoleConstants.ATTR_PLUGIN_ROOT ) + "/";
        pw.println("<li><a href='" + pluginRoot + TAB_PROPS + ".nfo'>" + TAB_PROPS + "</a></li>");
        pw.println("<li><a href='" + pluginRoot + TAB_THREADS + ".nfo'>" + TAB_THREADS + "</a></li>");

        // print header for printers
        Collection printers = getConfigurationPrinters();
        for (Iterator i = printers.iterator(); i.hasNext();)
        {
            final PrinterDesc desc = (PrinterDesc) i.next();
            final String title = desc.printer.getTitle();
            pw.print("<li><a href='" + pluginRoot + title + ".nfo'>" + title + "</a></li>" );
        }
        pw.println("</ul> <!-- end tabs on top -->");
        pw.println();

        pw.println("</div> <!-- end tabs container -->");

        pw.flush();
    }


    private void printConfigurationStatus( ConfigurationWriter pw, final String mode )
    {
        printSystemProperties( pw );
        printThreads( pw );

        for ( Iterator cpi = getConfigurationPrinters().iterator(); cpi.hasNext(); )
        {
            final PrinterDesc desc = (PrinterDesc) cpi.next();
            if ( desc.match(mode) )
            {
                printConfigurationPrinter( pw, desc.printer, mode );
            }
        }
    }


    private final Collection getConfigurationPrinters()
    {
        if ( cfgPrinterTracker == null )
        {
            cfgPrinterTracker = new ServiceTracker( getBundleContext(), ConfigurationPrinter.SERVICE, null );
            cfgPrinterTracker.open();
            cfgPrinterTrackerCount = -1;
        }

        if ( cfgPrinterTrackerCount != cfgPrinterTracker.getTrackingCount() )
        {
            SortedMap cp = new TreeMap();
            ServiceReference[] refs = cfgPrinterTracker.getServiceReferences();
            if ( refs != null )
            {
                for ( int i = 0; i < refs.length; i++ )
                {
                    ConfigurationPrinter cfgPrinter =  ( ConfigurationPrinter ) cfgPrinterTracker.getService(refs[i]);
                    if ( cfgPrinter != null )
                    {
                        cp.put( cfgPrinter.getTitle(), new PrinterDesc(cfgPrinter, refs[i].getProperty(ConfigurationPrinter.PROPERTY_MODES)) );
                    }
                }
            }
            configurationPrinters = cp;
            cfgPrinterTrackerCount = cfgPrinterTracker.getTrackingCount();
        }

        return configurationPrinters.values();
    }


    private static final void printSystemProperties( ConfigurationWriter pw )
    {
        pw.title( "System properties" );

        Properties props = System.getProperties();
        SortedSet keys = new TreeSet( props.keySet() );
        for ( Iterator ki = keys.iterator(); ki.hasNext(); )
        {
            Object key = ki.next();
            infoLine( pw, null, ( String ) key, props.get( key ) );
        }

        pw.end();
    }


    // This is Sling stuff, we comment it out for now
    //    private void printRawFrameworkProperties(PrintWriter pw) {
    //        pw.println("*** Raw Framework properties:");
    //
    //        File file = new File(getBundleContext().getProperty("sling.home"),
    //            "sling.properties");
    //        if (file.exists()) {
    //            Properties props = new Properties();
    //            InputStream ins = null;
    //            try {
    //                ins = new FileInputStream(file);
    //                props.load(ins);
    //            } catch (IOException ioe) {
    //                // handle or ignore
    //            } finally {
    //                IOUtils.closeQuietly(ins);
    //            }
    //
    //            SortedSet keys = new TreeSet(props.keySet());
    //            for (Iterator ki = keys.iterator(); ki.hasNext();) {
    //                Object key = ki.next();
    //                infoLine(pw, null, (String) key, props.get(key));
    //            }
    //
    //        } else {
    //            pw.println("  No Framework properties in " + file);
    //        }
    //
    //        pw.println();
    //    }


    private static final void printConfigurationPrinter( final ConfigurationWriter pw,
                                            final ConfigurationPrinter cp,
                                            final String mode )
    {
        pw.title(  cp.getTitle() );
        if ( cp instanceof ModeAwareConfigurationPrinter )
        {
            ((ModeAwareConfigurationPrinter)cp).printConfiguration( pw , mode);
        }
        else
        {
            cp.printConfiguration( pw );
        }
        pw.end();
    }


    /**
     * Renders an info line - element in the framework configuration. The info line will
     * look like:
     * <pre>
     * label = value
     * </pre>
     *
     * Optionally it can be indented by a specific string.
     *
     * @param pw the writer to print to
     * @param indent indentation string
     * @param label the label data
     * @param value the data itself.
     */
    public static final void infoLine( PrintWriter pw, String indent, String label, Object value )
    {
        if ( indent != null )
        {
            pw.print( indent );
        }

        if ( label != null )
        {
            pw.print( label );
            pw.print( " = " );
        }

        pw.print( asString( value ) );

        pw.println();
    }


    private static final String asString( final Object value )
    {
        if ( value == null )
        {
            return "n/a";
        }
        else if ( value.getClass().isArray() )
        {
            StringBuffer dest = new StringBuffer();
            Object[] values = ( Object[] ) value;
            for ( int j = 0; j < values.length; j++ )
            {
                if ( j > 0 )
                    dest.append( ", " );
                dest.append( values[j] );
            }
            return dest.toString();
        }
        else
        {
            return value.toString();
        }
    }


    private static final void printThreads( ConfigurationWriter pw )
    {
        // first get the root thread group
        ThreadGroup rootGroup = Thread.currentThread().getThreadGroup();
        while ( rootGroup.getParent() != null )
        {
            rootGroup = rootGroup.getParent();
        }

        pw.title(  "Threads" );

        printThreadGroup( pw, rootGroup );

        int numGroups = rootGroup.activeGroupCount();
        ThreadGroup[] groups = new ThreadGroup[2 * numGroups];
        rootGroup.enumerate( groups );
        for ( int i = 0; i < groups.length; i++ )
        {
            printThreadGroup( pw, groups[i] );
        }

        pw.end();
    }


    private static final void printThreadGroup( PrintWriter pw, ThreadGroup group )
    {
        if ( group != null )
        {
            StringBuffer info = new StringBuffer();
            info.append("ThreadGroup ").append(group.getName());
            info.append( " [" );
            info.append( "maxprio=" ).append( group.getMaxPriority() );

            info.append( ", parent=" );
            if ( group.getParent() != null )
            {
                info.append( group.getParent().getName() );
            }
            else
            {
                info.append( '-' );
            }

            info.append( ", isDaemon=" ).append( group.isDaemon() );
            info.append( ", isDestroyed=" ).append( group.isDestroyed() );
            info.append( ']' );

            infoLine( pw, null, null, info.toString() );

            int numThreads = group.activeCount();
            Thread[] threads = new Thread[numThreads * 2];
            group.enumerate( threads, false );
            for ( int i = 0; i < threads.length; i++ )
            {
                printThread( pw, threads[i] );
            }

            pw.println();
        }
    }


    private static final void printThread( PrintWriter pw, Thread thread )
    {
        if ( thread != null )
        {
            StringBuffer info = new StringBuffer();
            info.append("Thread ").append( thread.getName() );
            info.append( " [" );
            info.append( "priority=" ).append( thread.getPriority() );
            info.append( ", alive=" ).append( thread.isAlive() );
            info.append( ", daemon=" ).append( thread.isDaemon() );
            info.append( ", interrupted=" ).append( thread.isInterrupted() );
            info.append( ", loader=" ).append( thread.getContextClassLoader() );
            info.append( ']' );

            infoLine( pw, "  ", null, info.toString() );
        }
    }

    private abstract static class ConfigurationWriter extends PrintWriter
    {

        ConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        abstract void title( String title );


        abstract void end();

        public void handleAttachments(final String title, final URL[] urls)
        throws IOException
        {
            throw new UnsupportedOperationException("handleAttachments not supported by this configuration writer: " + this);
        }

    }

    private static class HtmlConfigurationWriter extends ConfigurationWriter
    {

        // whether or not to filter "<" signs in the output
        private boolean doFilter;


        HtmlConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        public void title( String title )
        {
            doFilter = true;
        }


        public void end()
        {
            doFilter = false;
        }


        // IE has an issue with white-space:pre in our case so, we write
        // <br/> instead of [CR]LF to get the line break. This also works
        // in other browsers.
        public void println()
        {
            if ( doFilter )
            {
                super.write( "<br/>", 0, 5 );
            }
            else
            {
                super.println();
            }
        }


        // write the character unmodified unless filtering is enabled and
        // the character is a "<" in which case &lt; is written
        public void write( final int character )
        {
            if ( doFilter && character == '<' )
            {
                super.write( "&lt;" );
            }
            else
            {
                super.write( character );
            }
        }


        // write the characters unmodified unless filtering is enabled in
        // which case the writeFiltered(String) method is called for filtering
        public void write( final char[] chars, final int off, final int len )
        {
            if ( doFilter )
            {
                writeFiltered( new String( chars, off, len ) );
            }
            else
            {
                super.write( chars, off, len );
            }
        }


        // write the string unmodified unless filtering is enabled in
        // which case the writeFiltered(String) method is called for filtering
        public void write( final String string, final int off, final int len )
        {
            if ( doFilter )
            {
                writeFiltered( string.substring( off, len ) );
            }
            else
            {
                super.write( string, off, len );
            }
        }


        // helper method filter the string for "<" before writing
        private void writeFiltered( String string )
        {
            string = WebConsoleUtil.escapeHtml(string); // filtering
            super.write( string, 0, string.length() );
        }
    }

    private void addAttachments( final ConfigurationWriter cf, final String mode )
    throws IOException
    {
        for ( Iterator cpi = getConfigurationPrinters().iterator(); cpi.hasNext(); )
        {
            // check if printer supports zip mode
            final PrinterDesc desc = (PrinterDesc) cpi.next();
            if ( desc.match(mode) )
            {
                // check if printer implements binary configuration printer
                if ( desc.printer instanceof AttachmentProvider )
                {
                    final URL[] attachments = ((AttachmentProvider)desc.printer).getAttachments(mode);
                    if ( attachments != null )
                    {
                        cf.handleAttachments(desc.printer.getTitle(), attachments);
                    }
                }
            }
        }

    }

    private static final class PrinterDesc
    {
        private final String[] modes;
        public final ConfigurationPrinter printer;

        private static final List CUSTOM_MODES = new ArrayList();
        static
        {
            CUSTOM_MODES.add(ConfigurationPrinter.MODE_TXT);
            CUSTOM_MODES.add(ConfigurationPrinter.MODE_WEB);
            CUSTOM_MODES.add(ConfigurationPrinter.MODE_ZIP);
        }

        public PrinterDesc(final ConfigurationPrinter printer, final Object modes)
        {
            this.printer = printer;
            if ( modes == null || !(modes instanceof String || modes instanceof String[]) )
            {
                this.modes = null;
            }
            else
            {
                if ( modes instanceof String )
                {
                    if ( CUSTOM_MODES.contains(modes) )
                    {
                        this.modes = new String[] {modes.toString()};
                    }
                    else
                    {
                        this.modes = null;
                    }
                }
                else
                {
                    final String[] values = (String[])modes;
                    boolean valid = values.length > 0;
                    for(int i=0; i<values.length; i++)
                    {
                        if ( !CUSTOM_MODES.contains(values[i]) )
                        {
                            valid = false;
                            break;
                        }
                    }
                    if ( valid)
                    {
                        this.modes = values;
                    }
                    else
                    {
                        this.modes = null;
                    }
                }
            }
        }

        public boolean match(final String mode)
        {
            if ( this.modes == null)
            {
                return true;
            }
            for(int i=0; i<this.modes.length; i++)
            {
                if ( this.modes[i].equals(mode) )
                {
                    return true;
                }
            }
            return false;
        }
    }

    private static class PlainTextConfigurationWriter extends ConfigurationWriter
    {

        PlainTextConfigurationWriter( Writer delegatee )
        {
            super( delegatee );
        }


        public void title( String title )
        {
            print( "*** " );
            print( title );
            println( ":" );
        }


        public void end()
        {
            println();
        }
    }

    private static class ZipConfigurationWriter extends ConfigurationWriter
    {
        private final ZipOutputStream zip;

        private int counter;


        ZipConfigurationWriter( ZipOutputStream zip )
        {
            super( new OutputStreamWriter( zip ) );
            this.zip = zip;
        }


        public void title( String title )
        {
            String name = MessageFormat.format( "{0,number,000}-{1}.txt", new Object[]
                { new Integer( counter ), title } );

            counter++;

            ZipEntry entry = new ZipEntry( name );
            try
            {
                zip.putNextEntry( entry );
            }
            catch ( IOException ioe )
            {
                // should handle
            }
        }

        private OutputStream startFile( String title, String name)
        {
            final String path = MessageFormat.format( "{0,number,000}-{1}/{2}", new Object[]
                 { new Integer( counter ), title, name } );
            ZipEntry entry = new ZipEntry( path );
            try
            {
                zip.putNextEntry( entry );
            }
            catch ( IOException ioe )
            {
                // should handle
            }
            return zip;
        }

        public void handleAttachments( final String title, final URL[] attachments)
        throws IOException
        {
            for(int i = 0; i < attachments.length; i++)
            {
                final URL current = attachments[i];
                final String path = current.getPath();
                final String name;
                if ( path == null || path.length() == 0 )
                {
                    // sanity code, we should have a path, but if not let's
                    // just create some random name
                    name = "file" + Double.doubleToLongBits( Math.random() );
                }
                else
                {
                    final int pos = path.lastIndexOf('/');
                    name = (pos == -1 ? path : path.substring(pos + 1));
                }
                final OutputStream os = this.startFile(title, name);
                final InputStream is = current.openStream();
                try
                {
                    IOUtils.copy(is, os);
                }
                finally
                {
                    IOUtils.closeQuietly(is);
                }
                this.end();
            }
        }


        public void end()
        {
            flush();

            try
            {
                zip.closeEntry();
            }
            catch ( IOException ioe )
            {
                // should handle
            }
        }
    }
}
