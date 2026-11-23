package dev.automationse.pokesleeprater.engine

import org.junit.Assert.*
import org.junit.Test

class PokemonCatalogTest {
    @Test fun recognizeSpeciesFromHeader() {
        assertEquals("モンメン", PokemonCatalog.detect("モンメン\nLv.25\nとくいなもの\nきのみ"))
        assertEquals("ミュウツー", PokemonCatalog.detect("ポケモン：ミュウツー\nLv50"))
        assertEquals("ピカチュウ", PokemonCatalog.detect("ピカチュウ Lv.30"))
    }

    @Test fun genderedSpeciesAreNotSilentlyTruncated() {
        assertEquals("ニドラン♀", PokemonCatalog.detect("ニドラン♀"))
        assertEquals("ニドラン♂", PokemonCatalog.detect("ニドラン♂"))
    }

    @Test fun avoidAmbiguousAndNicknameGuesses() {
        assertNull(PokemonCatalog.detect("ピカチュウ\nフシギダネ"))
        assertNull(PokemonCatalog.detect("おてつだいボーナス\n元気な子"))
        assertNull(PokemonCatalog.detect("きのみの数S\nおてつだいスピードM"))
    }

    @Test fun detectSpecialtyOnlyWhenLabeled() {
        assertEquals(Specialty.BERRIES, PokemonCatalog.detectSpecialty("とくいなもの\nきのみ"))
        assertEquals(Specialty.INGREDIENTS, PokemonCatalog.detectSpecialty("とくいなもの:食材"))
        assertEquals(Specialty.SKILLS, PokemonCatalog.detectSpecialty("得意なもの\nスキル"))
        assertNull(PokemonCatalog.detectSpecialty("きのみの数S\nスキル確率アップM"))
    }

    @Test fun catalogCoversAllGenerationsNotOnlySleep() {
        assertTrue(PokemonCatalog.names.size >= 1000)
        assertTrue("コモルー" in PokemonCatalog.names)
        assertTrue("ハクリュー" in PokemonCatalog.names)
        assertTrue("ドードリオ" in PokemonCatalog.names)
    }
}
