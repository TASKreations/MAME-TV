package com.taskreations.mametv;

import java.io.*;
import java.net.*;

public final class RomDownloader {
    private RomDownloader() {}

    public static URL checkedUrl(String text) throws IOException {
        URL url = new URL(text);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().isEmpty() || url.getUserInfo() != null)
            throw new IOException("Enter an HTTPS download URL without embedded credentials.");
        return url;
    }

    public static File download(RomStore store, String address, String name) throws IOException {
        RomStore.filename(name);
        URL url = checkedUrl(address);
        for (int redirect = 0; redirect <= 5; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MAME-TV/0.1");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Download redirect has no destination.");
                    url = checkedUrl(new URL(url, location).toString());
                    continue;
                }
                if (status != 200) throw new IOException("Download server returned HTTP " + status);
                if (connection.getContentLengthLong() > RomStore.MAX_BYTES) throw new IOException("Download exceeds 512 MB.");
                try (InputStream input = connection.getInputStream()) { return store.importZip(input, name); }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many download redirects.");
    }
}
