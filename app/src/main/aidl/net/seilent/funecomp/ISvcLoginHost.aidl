package net.seilent.funecomp;

import net.seilent.funecomp.ISvcLoginPage;

interface ISvcLoginHost {
    String[] ask(String token, ISvcLoginPage page);
    boolean caught(String token, String url);
    boolean cancelled(String token);
    boolean closed(String token);
}
