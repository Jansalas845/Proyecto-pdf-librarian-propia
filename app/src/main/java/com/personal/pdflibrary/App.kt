package com.personal.pdflibrary

import android.app.Application
import com.personal.pdflibrary.data.Repo

class App : Application() {
    val repo by lazy { Repo(this) }
}
