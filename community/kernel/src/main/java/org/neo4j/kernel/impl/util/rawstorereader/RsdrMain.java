/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.util.rawstorereader;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.bind.DatatypeConverter;

import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.pagecache.PageCacheMonitor;
import org.neo4j.io.pagecache.PageSwapperFactory;
import org.neo4j.io.pagecache.impl.SingleFilePageSwapperFactory;
import org.neo4j.kernel.DefaultFileSystemAbstraction;
import org.neo4j.kernel.DefaultIdGeneratorFactory;
import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.pagecache.LifecycledPageCache;
import org.neo4j.kernel.impl.store.InvalidRecordException;
import org.neo4j.kernel.impl.store.NeoStore;
import org.neo4j.kernel.impl.store.RecordStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.transaction.log.IOCursor;
import org.neo4j.kernel.impl.transaction.log.LogDeserializer;
import org.neo4j.kernel.impl.transaction.log.PhysicalLogVersionedStoreChannel;
import org.neo4j.kernel.impl.transaction.log.ReadAheadLogChannel;
import org.neo4j.kernel.impl.transaction.log.ReadableVersionableLogChannel;
import org.neo4j.kernel.impl.transaction.log.entry.LogEntry;
import org.neo4j.kernel.impl.transaction.log.entry.LogHeader;
import org.neo4j.kernel.impl.util.Neo4jJobScheduler;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.monitoring.Monitors;

import static javax.transaction.xa.Xid.MAXBQUALSIZE;
import static javax.transaction.xa.Xid.MAXGTRIDSIZE;

import static org.neo4j.kernel.impl.transaction.log.LogVersionBridge.NO_MORE_CHANNELS;
import static org.neo4j.kernel.impl.transaction.log.entry.LogHeaderReader.readLogHeader;

public class RsdrMain
{
    private static final FileSystemAbstraction files = new DefaultFileSystemAbstraction();
    private static final Console console = System.console();
    private static final Pattern readCommandPattern = Pattern.compile( "r" + // 'r' means read command
            "((?<lower>\\d+)?,(?<upper>\\d+)?)?\\s+" + // optional record id range bounds, followed by whitespace
            "(?<fname>[\\w\\.]+)" + // files are a sequence of word characters or literal '.'
            "(\\s*\\|\\s*(?<regex>.+))?" // a pipe signifies a regex to filter records by
    );

    public static void main( String[] args ) throws IOException
    {
        console.printf("Neo4j Raw Store Diagnostics Reader%n");

        if ( args.length != 1 || !files.isDirectory( new File( args[0] ) ) )
        {
            console.printf("Usage: rsdr <storepath>%n");
            return;
        }

        File storepath = new File( args[0] );
        StoreFactory factory = openStore( storepath );
        NeoStore neoStore = factory.newNeoStore( false );
        interact( neoStore );
    }

    private static StoreFactory openStore( File storepath )
    {
        Config config = new Config( MapUtil.stringMap(
                GraphDatabaseSettings.read_only.name(), "true",
                GraphDatabaseSettings.mapped_memory_total_size.name(), "64M",
                GraphDatabaseSettings.neo_store.name(), storepath.getAbsolutePath() + File.separator + NeoStore.DEFAULT_NAME ) );
        IdGeneratorFactory idGeneratorFactory = new DefaultIdGeneratorFactory();
        Neo4jJobScheduler jobScheduler = new Neo4jJobScheduler();
        jobScheduler.init();
        PageSwapperFactory swapperFactory = new SingleFilePageSwapperFactory( files );
        LifecycledPageCache pageCache = new LifecycledPageCache(
                swapperFactory, jobScheduler, config, new Monitors().newMonitor( PageCacheMonitor.class ) );
        pageCache.start();
        StringLogger logger = StringLogger.DEV_NULL;
        StoreFactory factory = new StoreFactory(
                config, idGeneratorFactory, pageCache, files, logger, null );
        return factory;
    }

    private static void interact( NeoStore neoStore ) throws IOException
    {
        printHelp();

        String cmd;
        do
        {
            cmd = console.readLine( "neo? " );
        } while ( execute( cmd, neoStore ) );
        System.exit( 0 );
    }

    private static void printHelp()
    {
        console.printf( "Usage:%n" +
                "  h            print this message%n" +
                "  l            list store files in store%n" +
                "  r f          read all records in store file 'f'%n" +
                "  r5,10 f      read record 5 through 10 in store file 'f'%n" +
                "  r f | rx     read records and filter through regex 'rx'%n" +
                "  q            quit%n" );
    }

    private static boolean execute( String cmd, NeoStore neoStore ) throws IOException
    {
        if ( cmd == null || cmd.equals( "q" ) )
        {
            return false;
        }
        else if ( cmd.equals( "h" ) )
        {
            printHelp();
        }
        else if ( cmd.equals( "l" ) )
        {
            listFiles( neoStore );
        }
        else if ( cmd.startsWith( "r" ) )
        {
            read( cmd, neoStore );
        }
        else if ( !cmd.trim().isEmpty() )
        {
            console.printf( "unrecognized command%n" );
        }
        return true;
    }

    private static void listFiles( NeoStore neoStore )
    {
        File storedir = neoStore.getStorageFileName().getParentFile();
        File[] listing = files.listFiles( storedir );
        for ( File file : listing )
        {
            console.printf( "%s%n", file.getName() );
        }
    }

    private static void read( String cmd, NeoStore neoStore ) throws IOException
    {
        Matcher matcher = readCommandPattern.matcher( cmd );
        if ( matcher.find() )
        {
            String lower = matcher.group( "lower" );
            String upper = matcher.group( "upper" );
            String fname = matcher.group( "fname" );
            String regex = matcher.group( "regex" );
            Pattern pattern = regex != null? Pattern.compile( regex ) : null;
            long fromId = lower != null? Long.parseLong( lower ) : 0L;
            long toId = upper != null ? Long.parseLong( upper ) : Long.MAX_VALUE;

            RecordStore store = getStore( fname, neoStore );
            if ( store != null )
            {
                readStore( store, fromId, toId, pattern );
                return;
            }

            IOCursor<LogEntry> cursor = getLogCursor( fname, neoStore );
            if ( cursor != null )
            {
                readLog( cursor, fromId, toId, pattern );
                cursor.close();
                return;
            }

            console.printf( "don't know how to read '%s'%n", fname );
        }
        else
        {
            console.printf( "bad read command format%n" );
        }
    }

    private static void readStore(
            RecordStore store, long fromId, long toId, Pattern pattern ) throws IOException
    {
        toId = Math.min( toId, store.getHighId() );
        try ( StoreChannel channel = files.open( store.getStorageFileName(), "r" ) )
        {
            int recordSize = store.getRecordSize();
            ByteBuffer buf = ByteBuffer.allocate( recordSize );
            for ( long i = fromId; i <= toId; i++ )
            {
                buf.clear();
                long offset = recordSize * i;
                int count = channel.read( buf, offset );
                if ( count == -1 )
                {
                    break;
                }
                byte[] bytes = new byte[count];
                buf.clear();
                buf.get( bytes );
                String hex = DatatypeConverter.printHexBinary( bytes );
                int paddingNeeded = (recordSize * 2 - Math.max( count * 2, 0 )) + 1;
                String format = "%s %6s 0x%08X %s%" + paddingNeeded + "s%s%n";
                String str;
                String use;

                try
                {
                    AbstractBaseRecord record = store.getRecord( i );
                    use = record.inUse()? "+" : "-";
                    str = record.toString();
                }
                catch ( InvalidRecordException e )
                {
                    str = new String( bytes, 0, count, "ASCII" );
                    use = "?";
                }

                if ( pattern == null || pattern.matcher( str ).find() )
                {
                    console.printf( format, use, i, offset, hex, " ", str );
                }
            }
        }
    }

    private static RecordStore getStore( String fname, NeoStore neoStore )
    {
        switch ( fname )
        {
            case "neostore.nodestore.db": return neoStore.getNodeStore();
            case "neostore.labeltokenstore.db": return neoStore.getLabelTokenStore();
            case "neostore.propertystore.db.index": return neoStore.getPropertyKeyTokenStore();
            case "neostore.propertystore.db": return neoStore.getPropertyStore();
            case "neostore.relationshipgroupstore.db": return neoStore.getRelationshipGroupStore();
            case "neostore.relationshipstore.db": return neoStore.getRelationshipStore();
            case "neostore.relationshiptypestore.db": return neoStore.getRelationshipTypeTokenStore();
            case "neostore.schemastore.db": return neoStore.getSchemaStore();
        }
        return null;
    }

    private static IOCursor<LogEntry> getLogCursor( String fname, NeoStore neoStore ) throws IOException
    {
        File file = new File( neoStore.getStorageFileName().getParent(), fname );
        StoreChannel fileChannel = files.open( file, "r" );
        ByteBuffer buffer = ByteBuffer.allocateDirect( 9 + MAXGTRIDSIZE + MAXBQUALSIZE * 10 );
        LogHeader logHeader = readLogHeader( buffer, fileChannel, false );
        console.printf( "Logical log version: %s with prev committed tx[%s]%n",
                logHeader.logVersion, logHeader.lastCommittedTxId );

        LogDeserializer deserializer = new LogDeserializer();

        PhysicalLogVersionedStoreChannel channel =
                new PhysicalLogVersionedStoreChannel( fileChannel, logHeader.logVersion, logHeader.logFormatVersion );
        ReadableVersionableLogChannel logChannel = new ReadAheadLogChannel( channel, NO_MORE_CHANNELS, 4096 );
        return deserializer.logEntries( logChannel );
    }

    private static void readLog(
            IOCursor<LogEntry> cursor,
            final long fromLine,
            final long toLine,
            final Pattern pattern ) throws IOException
    {
        TimeZone timeZone = TimeZone.getDefault();
        long lineCount = -1;
        while ( cursor.next() )
        {
            LogEntry logEntry = cursor.get();
            lineCount++;
            if ( lineCount > toLine )
            {
                return;
            }
            if ( lineCount < fromLine )
            {
                continue;
            }
            String str = logEntry.toString( timeZone );
            if ( pattern == null || pattern.matcher( str ).find() )
            {
                console.printf( "%s%n", str );
            }
        }
    }
}
