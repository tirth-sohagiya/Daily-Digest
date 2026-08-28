package com.tirth.digest.sources;

import com.tirth.digest.model.Section;

public interface Source {

    String title();

    /** Implementations may throw freely; callers must isolate the failure and still render every other section. */
    Section fetch() throws Exception;
}
