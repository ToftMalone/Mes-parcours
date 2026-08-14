package com.example

import com.example.util.KmlStyleTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Google Earth n'écrit pas la couleur dans le tracé : le `<Placemark>` renvoie à un
 * style par son identifiant, et ce style peut être un `<StyleMap>` qui distingue
 * l'apparence au repos de celle au survol.
 *
 * Ces tests fixent la résolution, y compris le cas qui a motivé tout ce détour : un
 * fichier réunissant plusieurs trajets, chacun renvoyant à son propre style.
 */
class KmlStyleTableTest {

    private val rouge = 0xFFFF0000.toInt()
    private val bleu = 0xFF0000FF.toInt()
    private val vert = 0xFF00FF00.toInt()

    @Test
    fun `un styleUrl direct donne la couleur du style`() {
        val table = KmlStyleTable()
        table.putStyle("trace1", rouge)

        assertEquals(rouge, table.resolve("#trace1"))
    }

    @Test
    fun `le croisillon est facultatif`() {
        val table = KmlStyleTable()
        table.putStyle("#trace1", rouge)

        assertEquals(rouge, table.resolve("trace1"))
    }

    @Test
    fun `un StyleMap mène au style au repos, pas à celui de survol`() {
        // C'est tout l'intérêt de la résolution : prendre le premier style venu
        // ramènerait la couleur de survol, que l'utilisateur ne voit jamais.
        val table = KmlStyleTable()
        table.putStyle("repos", rouge)
        table.putStyle("survol", bleu)
        table.putStyleMap("carte", "#repos")

        assertEquals(rouge, table.resolve("#carte"))
    }

    @Test
    fun `chaque trajet garde sa couleur`() {
        // Le cas réel : un export Google Earth réunissant plusieurs voyages.
        val table = KmlStyleTable()
        table.putStyle("hendaye", rouge)
        table.putStyle("savoie", bleu)
        table.putStyle("dubai", vert)

        assertEquals(rouge, table.resolve("#hendaye"))
        assertEquals(bleu, table.resolve("#savoie"))
        assertEquals(vert, table.resolve("#dubai"))
    }

    @Test
    fun `un style inconnu ne donne pas de couleur`() {
        val table = KmlStyleTable()
        table.putStyle("trace1", rouge)

        assertNull(table.resolve("#absent"))
        assertNull(table.resolve(null))
        assertNull(table.resolve(""))
    }

    @Test
    fun `un style sans couleur de ligne ne donne pas de couleur`() {
        // Un <Style> qui ne porte qu'une icône ou un remplissage : rien à en tirer
        // pour le tracé, et surtout pas une couleur inventée.
        val table = KmlStyleTable()
        table.putStyle("iconeSeule", null)

        assertNull(table.resolve("#iconeSeule"))
    }

    @Test
    fun `deux StyleMap qui se renvoient l un à l autre ne bloquent pas l import`() {
        // Rien dans le format ne l'interdit, et une résolution naïve tournerait en
        // boucle — sur un fichier de plusieurs centaines de Mo, l'import ne
        // reviendrait jamais.
        val table = KmlStyleTable()
        table.putStyleMap("a", "#b")
        table.putStyleMap("b", "#a")

        assertNull(table.resolve("#a"))
    }
}
