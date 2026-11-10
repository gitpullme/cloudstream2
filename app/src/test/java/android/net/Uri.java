package android.net;

import java.io.Serializable;

public abstract class Uri implements Comparable<Uri>, Serializable {
    public static final Uri EMPTY = new StringUri("");

    public abstract String getScheme();
    public abstract String getAuthority();
    public abstract String getEncodedAuthority();
    public abstract String getHost();
    public abstract int getPort();
    public abstract String getPath();
    public abstract String getQuery();
    public abstract String getFragment();
    public abstract Builder buildUpon();

    public String getQueryParameter(String key) {
        String q = getQuery();
        if (q == null || q.isEmpty()) return null;
        for (String param : q.split("&")) {
            String[] pair = param.split("=", 2);
            if (pair[0].equals(key)) {
                return pair.length > 1 ? pair[1] : "";
            }
        }
        return null;
    }

    public static Uri parse(String uriString) {
        if (uriString == null) return null;
        return new StringUri(uriString);
    }

    public static Uri fromFile(java.io.File file) {
        if (file == null) return null;
        return new FileUri(file);
    }

    @Override
    public int compareTo(Uri other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Uri)) return false;
        return toString().equals(o.toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    public static class Builder {
        private String scheme;
        private String authority;
        private String path = "";
        private String query;
        private String fragment;

        public Builder scheme(String scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder authority(String authority) {
            this.authority = authority;
            return this;
        }

        public Builder encodedAuthority(String authority) {
            this.authority = authority;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder encodedPath(String path) {
            this.path = path;
            return this;
        }

        public Builder appendPath(String newSegment) {
            if (newSegment == null) return this;
            if (path == null || path.isEmpty()) {
                path = "/" + newSegment;
            } else if (path.endsWith("/")) {
                path = path + newSegment;
            } else {
                path = path + "/" + newSegment;
            }
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder encodedQuery(String query) {
            this.query = query;
            return this;
        }

        public Builder appendQueryParameter(String key, String value) {
            String entry = key + "=" + (value != null ? value : "");
            if (query == null || query.isEmpty()) {
                query = entry;
            } else {
                query = query + "&" + entry;
            }
            return this;
        }

        public Builder fragment(String fragment) {
            this.fragment = fragment;
            return this;
        }

        public Builder clearQuery() {
            this.query = null;
            return this;
        }

        public Uri build() {
            StringBuilder sb = new StringBuilder();
            if (scheme != null) sb.append(scheme).append("://");
            if (authority != null) sb.append(authority);
            if (path != null) {
                if (authority != null && !path.startsWith("/")) sb.append("/");
                sb.append(path);
            }
            if (query != null && !query.isEmpty()) sb.append("?").append(query);
            if (fragment != null && !fragment.isEmpty()) sb.append("#").append(fragment);
            return Uri.parse(sb.toString());
        }
    }

    private static class StringUri extends Uri {
        private final String uriString;
        private final String scheme;
        private final String authority;
        private final String host;
        private final int port;
        private final String path;
        private final String query;
        private final String fragment;

        public StringUri(String uriString) {
            this.uriString = uriString;
            String rest = uriString;
            int schemeIdx = rest.indexOf("://");
            if (schemeIdx != -1) {
                this.scheme = rest.substring(0, schemeIdx);
                rest = rest.substring(schemeIdx + 3);
            } else {
                this.scheme = null;
            }

            int fragIdx = rest.indexOf('#');
            if (fragIdx != -1) {
                this.fragment = rest.substring(fragIdx + 1);
                rest = rest.substring(0, fragIdx);
            } else {
                this.fragment = null;
            }

            int queryIdx = rest.indexOf('?');
            if (queryIdx != -1) {
                this.query = rest.substring(queryIdx + 1);
                rest = rest.substring(0, queryIdx);
            } else {
                this.query = null;
            }

            if (scheme != null) {
                if ("file".equalsIgnoreCase(scheme)) {
                    this.authority = null;
                    if (rest.startsWith("///")) {
                        rest = rest.substring(2);
                    }
                    this.path = rest;
                } else {
                    int slashIdx = rest.indexOf('/');
                    if (slashIdx != -1) {
                        this.authority = rest.substring(0, slashIdx);
                        this.path = rest.substring(slashIdx);
                    } else {
                        this.authority = rest;
                        this.path = "";
                    }
                }
            } else {
                this.authority = null;
                this.path = rest;
            }

            if (authority != null) {
                int colonIdx = authority.lastIndexOf(':');
                if (colonIdx != -1) {
                    this.host = authority.substring(0, colonIdx);
                    int p = -1;
                    try {
                        p = Integer.parseInt(authority.substring(colonIdx + 1));
                    } catch (NumberFormatException ignored) {}
                    this.port = p;
                } else {
                    this.host = authority;
                    this.port = -1;
                }
            } else {
                this.host = null;
                this.port = -1;
            }
        }

        @Override public String getScheme() { return scheme; }
        @Override public String getAuthority() { return authority; }
        @Override public String getEncodedAuthority() { return authority; }
        @Override public String getHost() { return host; }
        @Override public int getPort() { return port; }
        @Override public String getPath() { return path; }
        @Override public String getQuery() { return query; }
        @Override public String getFragment() { return fragment; }
        @Override public String toString() { return uriString; }

        @Override
        public Builder buildUpon() {
            return new Builder()
                .scheme(scheme)
                .encodedAuthority(authority)
                .path(path)
                .encodedQuery(query)
                .fragment(fragment);
        }
    }

    private static class FileUri extends Uri {
        private final java.io.File file;

        public FileUri(java.io.File file) {
            this.file = file;
        }

        @Override public String getScheme() { return "file"; }
        @Override public String getAuthority() { return null; }
        @Override public String getEncodedAuthority() { return null; }
        @Override public String getHost() { return null; }
        @Override public int getPort() { return -1; }
        @Override public String getPath() { return file.getAbsolutePath(); }
        @Override public String getQuery() { return null; }
        @Override public String getFragment() { return null; }
        @Override public String toString() { return "file://" + file.getAbsolutePath(); }

        @Override
        public Builder buildUpon() {
            return new Builder()
                .scheme("file")
                .path(file.getAbsolutePath());
        }
    }
}
