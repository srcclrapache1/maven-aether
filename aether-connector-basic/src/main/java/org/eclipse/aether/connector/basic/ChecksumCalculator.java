package org.eclipse.aether.connector.basic;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.util.ChecksumUtils;

/**
 * Calculates checksums for a downloaded file.
 */
final class ChecksumCalculator
{

    static class Checksum
    {
        final String algorithm;

        final MessageDigest digest;

        Exception error;

        public Checksum( String algorithm )
        {
            this.algorithm = algorithm;
            MessageDigest digest = null;
            try
            {
                digest = MessageDigest.getInstance( algorithm );
            }
            catch ( NoSuchAlgorithmException e )
            {
                error = e;
            }
            this.digest = digest;
        }

        public void update( ByteBuffer buffer )
        {
            if ( digest != null )
            {
                digest.update( buffer );
            }
        }

        public void reset()
        {
            if ( digest != null )
            {
                digest.reset();
                error = null;
            }
        }

        public void error( Exception error )
        {
            if ( digest != null )
            {
                this.error = error;
            }
        }

        public Object get()
        {
            if ( error != null )
            {
                return error;
            }
            return ChecksumUtils.toHexString( digest.digest() );
        }

    }

    private final List<Checksum> checksums;

    private final File targetFile;

    public static ChecksumCalculator newInstance( File targetFile, Collection<RepositoryLayout.Checksum> checksums )
    {
        if ( checksums == null || checksums.isEmpty() )
        {
            return null;
        }
        return new ChecksumCalculator( targetFile, checksums );
    }

    private ChecksumCalculator( File targetFile, Collection<RepositoryLayout.Checksum> checksums )
    {
        this.checksums = new ArrayList<Checksum>();
        Set<String> algos = new HashSet<String>();
        for ( RepositoryLayout.Checksum checksum : checksums )
        {
            String algo = checksum.getAlgorithm();
            if ( algos.add( algo ) )
            {
                this.checksums.add( new Checksum( algo ) );
            }
        }
        this.targetFile = targetFile;
    }

    public void init( long dataOffset )
    {
        for ( Checksum checksum : checksums )
        {
            checksum.reset();
        }
        if ( dataOffset <= 0 )
        {
            return;
        }
        try
        {
            FileInputStream fis = new FileInputStream( targetFile );
            try
            {
                long total = 0;
                ByteBuffer buffer = ByteBuffer.allocate( 1024 * 32 );
                for ( byte[] array = buffer.array(); total < dataOffset; )
                {
                    int read = fis.read( array );
                    if ( read < 0 )
                    {
                        if ( total < dataOffset )
                        {
                            throw new IOException( targetFile + " contains only " + total
                                + " bytes, cannot resume download from offset " + dataOffset );
                        }
                        break;
                    }
                    total += read;
                    if ( total > dataOffset )
                    {
                        read -= total - dataOffset;
                    }
                    buffer.rewind();
                    buffer.limit( read );
                    update( buffer );
                }
            }
            finally
            {
                try
                {
                    fis.close();
                }
                catch ( IOException e )
                {
                    // irrelevant
                }
            }
        }
        catch ( IOException e )
        {
            for ( Checksum checksum : checksums )
            {
                checksum.error( e );
            }
        }
    }

    public void update( ByteBuffer data )
    {
        for ( Checksum checksum : checksums )
        {
            data.mark();
            checksum.update( data );
            data.reset();
        }
    }

    public Map<String, Object> get()
    {
        Map<String, Object> results = new HashMap<String, Object>();
        for ( Checksum checksum : checksums )
        {
            results.put( checksum.algorithm, checksum.get() );
        }
        return results;
    }

}
