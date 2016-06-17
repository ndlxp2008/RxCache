/*
 * Copyright 2015 Victor Albertos
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.rx_cache.internal;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import io.rx_cache.JsonConverter;
import io.rx_cache.internal.encrypt.FileEncryptor;

/**
 * Save objects in disk and evict them too. It uses Gson as json parser.
 */
public final class Disk implements Persistence {
    private final File cacheDirectory;
    private final FileEncryptor fileEncryptor;
    private final JsonConverter jsonConverter;

    @Inject public Disk(File cacheDirectory, FileEncryptor fileEncryptor, JsonConverter jsonConverter) {
        this.cacheDirectory = cacheDirectory;
        this.fileEncryptor = fileEncryptor;
        this.jsonConverter = jsonConverter;
    }

    /** Save in disk the Record passed.
     * @param key the key whereby the Record could be retrieved/deleted later. @see evict and @see retrieve.
     * @param record the record to be persisted.
     * @param isEncrypted If the persisted record is encrypted or not. See {@link io.rx_cache.Encrypt}
     * @param encryptKey The key used to encrypt/decrypt the record to be persisted. See {@link io.rx_cache.EncryptKey}
     * */
    @Override public void saveRecord(String key, Record record, boolean isEncrypted, String encryptKey) {
        save(key, record, isEncrypted, encryptKey);
    }

    /**
     * Retrieve the names from all files in dir
     */
    @Override public List<String> allKeys() {
        List<String> nameFiles = new ArrayList<>();

        File[] files = cacheDirectory.listFiles();
        if(files == null) return nameFiles;

        for (File file : files) {
            if (file.isFile()) {
                nameFiles.add(file.getName());
            }
        }

        return nameFiles;
    }


    /**
     * Retrieve records accumulated memory in megabyte
     */
    @Override public int storedMB() {
        long bytes = 0;

        final File[] files = cacheDirectory.listFiles();
        if (files == null) return 0;

        for (File file: files) {
            bytes += file.length();
        }

        double megabytes = Math.ceil((double)bytes/1024/1024);
        return (int) megabytes;
    }

    /** Save in disk the object passed.
     * @param key the key whereby the object could be retrieved/deleted later. @see evict and @see retrieve.
     * @param data the object to be persisted.
     * @param isEncrypted If the persisted record is encrypted or not. See {@link io.rx_cache.Encrypt}
     * @param encryptKey The key used to encrypt/decrypt the record to be persisted. See {@link io.rx_cache.EncryptKey}
     * */
    public void save(String key, Object data, boolean isEncrypted, String encryptKey) {
        String wrapperJSONSerialized = jsonConverter.toJson(data);
        FileWriter fileWriter = null;

        try {
            File file = new File(cacheDirectory, key);
            fileWriter = new FileWriter(file, false);
            fileWriter.write(wrapperJSONSerialized);
            fileWriter.flush();
            fileWriter.close();
            fileWriter = null;

            if (isEncrypted)
                fileEncryptor.encrypt(encryptKey, new File(cacheDirectory, key));

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        } finally {
            try {
                if (fileWriter != null) {
                    fileWriter.flush();
                    fileWriter.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Delete the object previously saved.
     * @param key the key whereby the object could be deleted.
     * */
    @Override public void evict(String key) {
        final File file = new File(cacheDirectory, key);
        file.delete();
    }

    /** Delete all objects previously saved.
     * */
    @Override public void evictAll() {
        for(File file: cacheDirectory.listFiles()) {
            file.delete();
        }
    }

    /** Retrieve the object previously saved.
     * @param key the key whereby the object could be retrieved.
     * @param clazz the type of class against the object need to be serialized
     * @param isEncrypted If the persisted record is encrypted or not. See {@link io.rx_cache.Encrypt}
     * @param encryptKey The key used to encrypt/decrypt the record to be persisted. See {@link io.rx_cache.EncryptKey}
     * */
    public <T> T retrieve(String key, final Class<T> clazz, boolean isEncrypted, String encryptKey) {
        File file = new File(cacheDirectory, key);
        BufferedReader bufferedReader = null;

        if (isEncrypted)
            file = fileEncryptor.decrypt(encryptKey, file);

        try {
            bufferedReader = new BufferedReader(new FileReader(file.getAbsoluteFile()));
            T data = jsonConverter.fromJson(bufferedReader, clazz);

            if (isEncrypted)
                file.delete();

            return data;
        } catch (Exception ignore) {
            return null;
        } finally {
            if (isEncrypted)
                file.delete();

            try {
                if (bufferedReader != null) {
                    bufferedReader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Retrieve the Record previously saved.
     * @param key the key whereby the object could be retrieved.
     * @param isEncrypted If the persisted record is encrypted or not. See {@link io.rx_cache.Encrypt}
     * @param encryptKey The key used to encrypt/decrypt the record to be persisted. See {@link io.rx_cache.EncryptKey}
     * */
    @Override public <T> Record<T> retrieveRecord(String key, boolean isEncrypted, String encryptKey) {
        BufferedReader readerTempRecord = null;
        BufferedReader reader = null;
        File file = new File(cacheDirectory, key);

        try {
            if (isEncrypted)
                file = fileEncryptor.decrypt(encryptKey, file);

            readerTempRecord = new BufferedReader(new FileReader(file.getAbsoluteFile()));

            Record tempDiskRecord = jsonConverter.fromJson(readerTempRecord, Record.class);
            readerTempRecord.close();

            reader = new BufferedReader(new FileReader(file.getAbsoluteFile()));
            Class classData = Class.forName(tempDiskRecord.getDataClassName());
            Class classCollectionData = tempDiskRecord.getDataCollectionClassName() == null
                    ? Object.class : Class.forName(tempDiskRecord.getDataCollectionClassName());

            boolean isCollection = Collection.class.isAssignableFrom(classCollectionData);
            boolean isArray = classCollectionData.isArray();
            boolean isMap = Map.class.isAssignableFrom(classCollectionData);
            Record<T> diskRecord;

            if (isCollection) {
                Type typeCollection = jsonConverter.parameterizedTypeWithOwner(null, classCollectionData, classData);
                Type typeRecord = jsonConverter.parameterizedTypeWithOwner(null, Record.class, typeCollection, classData);
                diskRecord = jsonConverter.fromJson(reader, typeRecord);
            } else if (isArray) {
                Type typeRecord = jsonConverter.parameterizedTypeWithOwner(null, Record.class, classCollectionData);
                diskRecord = jsonConverter.fromJson(reader, typeRecord);
            } else if (isMap) {
                Class classKeyMap = Class.forName(tempDiskRecord.getDataKeyMapClassName());
                Type typeMap = jsonConverter.parameterizedTypeWithOwner(null, classCollectionData, classKeyMap, classData);
                Type typeRecord = jsonConverter.parameterizedTypeWithOwner(null, Record.class, typeMap, classData);
                diskRecord = jsonConverter.fromJson(reader, typeRecord);
            } else {
                Type type = jsonConverter.parameterizedTypeWithOwner(null, Record.class, classData);
                diskRecord = jsonConverter.fromJson(reader, type);
            }

            diskRecord.setSizeOnMb(file.length()/1024f/1024f);

            return diskRecord;
        } catch (Exception ignore) {
            return null;
        } finally {
            try {
                if (readerTempRecord != null) {
                    readerTempRecord.close();
                }
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (isEncrypted)
                file.delete();
        }
    }
    private String getFileContent(File file) {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(file.getAbsoluteFile()));
            String aux;

            while ((aux = reader.readLine()) != null) {
                builder.append(aux);
            }

        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return builder.toString();
    }

    /** Retrieve a collection previously saved.
     * @param key the key whereby the object could be retrieved.
     * @param classCollection type class collection
     * @param classData type class contained by the collection, not the collection itself
     * */
    public <C extends Collection<T>, T> C retrieveCollection(String key, Class<C> classCollection, Class<T> classData) {
        BufferedReader reader = null;

        try {
            File file = new File(cacheDirectory, key);
            reader = new BufferedReader(new FileReader(file.getAbsoluteFile()));

            Type typeCollection = jsonConverter.parameterizedTypeWithOwner(null, classCollection, classData);
            T data = jsonConverter.fromJson(reader, typeCollection);

            return (C) data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Retrieve a Map previously saved.
     * @param key the key whereby the object could be retrieved.
     * @param classMap type class Map
     * @param classMapKey type class of the Map key
     * @param classMapValue type class of the Map value
     * */
    public <M extends Map<K,V>, K, V> M retrieveMap(String key, Class classMap, Class<K> classMapKey, Class<V> classMapValue) {
        BufferedReader reader = null;

        try {
            File file = new File(cacheDirectory, key);
            reader = new BufferedReader(new FileReader(file.getAbsoluteFile()));

            Type typeMap = jsonConverter.parameterizedTypeWithOwner(null, classMap, classMapKey, classMapValue);
            Object data = jsonConverter.fromJson(reader, typeMap);

            return (M) data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /** Retrieve an Array previously saved.
     * @param key the key whereby the object could be retrieved.
     * @param classData type class contained by the Array
     * */
    public <T> T[] retrieveArray(String key, Class<T> classData) {
        BufferedReader reader = null;

        try {
            File file = new File(cacheDirectory, key);
            reader = new BufferedReader(new FileReader(file.getAbsoluteFile()));

            Class<?> clazzArray = Array.newInstance(classData, 1).getClass();
            Object data = jsonConverter.fromJson(reader, clazzArray);

            return (T[]) data;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

}