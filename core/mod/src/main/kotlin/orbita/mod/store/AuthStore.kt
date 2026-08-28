// В3: учётки, сессии, роли на проект. Пароль — PBKDF2 из JDK (внешних
// криптобиблиотек не заводим); сессия — случайный токен в БД с истечением.
package orbita.mod.store

import java.security.SecureRandom
import java.sql.Connection
import java.time.OffsetDateTime
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AuthUser(val login: String, val displayName: String)

class AuthStore(private val conn: Connection) {

    private val random = SecureRandom()

    /** Учётки заведены — сервер в многопользовательском режиме. */
    fun enabled(): Boolean = conn.createStatement().use { st ->
        st.executeQuery("SELECT count(*) FROM users").use { rs -> rs.next(); rs.getLong(1) > 0 }
    }

    fun createUser(login: String, password: String, displayName: String) {
        require(password.length >= 8) { "пароль короче 8 знаков" }
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = pbkdf2(password, salt)
        conn.prepareStatement(
            "INSERT INTO users(login, password_hash, salt, display_name) VALUES (?,?,?,?)"
        ).use { ps ->
            ps.setString(1, login)
            ps.setString(2, hash.toHex())
            ps.setString(3, salt.toHex())
            ps.setString(4, displayName)
            ps.executeUpdate()
        }
    }

    fun verify(login: String, password: String): AuthUser? =
        conn.prepareStatement("SELECT password_hash, salt, display_name FROM users WHERE login = ?")
            .use { ps ->
                ps.setString(1, login)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val expected = rs.getString(1)
                    val salt = rs.getString(2).fromHex()
                    if (pbkdf2(password, salt).toHex() == expected) {
                        AuthUser(login, rs.getString(3))
                    } else null
                }
            }

    fun createSession(login: String, days: Long = 30): String {
        val token = ByteArray(32).also(random::nextBytes).toHex()
        conn.prepareStatement(
            "INSERT INTO sessions(token, login, expires_at) VALUES (?,?,?)"
        ).use { ps ->
            ps.setString(1, token)
            ps.setString(2, login)
            ps.setObject(3, OffsetDateTime.now().plusDays(days))
            ps.executeUpdate()
        }
        return token
    }

    fun sessionUser(token: String): AuthUser? =
        conn.prepareStatement(
            """SELECT u.login, u.display_name FROM sessions s
               JOIN users u ON u.login = s.login
               WHERE s.token = ? AND s.expires_at > now()"""
        ).use { ps ->
            ps.setString(1, token)
            ps.executeQuery().use { rs -> if (rs.next()) AuthUser(rs.getString(1), rs.getString(2)) else null }
        }

    fun dropSession(token: String) {
        conn.prepareStatement("DELETE FROM sessions WHERE token = ?").use { ps ->
            ps.setString(1, token)
            ps.executeUpdate()
        }
    }

    fun setRole(projectId: String, login: String, role: String) {
        conn.prepareStatement(
            """INSERT INTO project_roles(project_id, login, role) VALUES (?,?,?)
               ON CONFLICT (project_id, login) DO UPDATE SET role = EXCLUDED.role"""
        ).use { ps ->
            ps.setString(1, projectId)
            ps.setString(2, login)
            ps.setString(3, role)
            ps.executeUpdate()
        }
    }

    fun roleIn(projectId: String?, login: String): String? =
        projectId?.let {
            conn.prepareStatement("SELECT role FROM project_roles WHERE project_id = ? AND login = ?")
                .use { ps ->
                    ps.setString(1, it)
                    ps.setString(2, login)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
        }

    fun rolesOf(login: String): Map<String, String> =
        conn.prepareStatement("SELECT project_id, role FROM project_roles WHERE login = ?").use { ps ->
            ps.setString(1, login)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
        }

    fun listRoles(projectId: String): Map<String, String> =
        conn.prepareStatement("SELECT login, role FROM project_roles WHERE project_id = ?").use { ps ->
            ps.setString(1, projectId)
            ps.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
        }

    /** Человеческое имя учётки — для показа авторов (круг 2 портфеля §1.3). */
    /** Учётки поимённо — пикеру исполнителя (МВП-П1: назначение заданий). */
    fun listUsers(): List<Pair<String, String>> =
        conn.prepareStatement("SELECT login, display_name FROM users ORDER BY login").use { st ->
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    fun displayNameOf(login: String): String? =
        conn.prepareStatement("SELECT display_name FROM users WHERE login = ?").use { ps ->
            ps.setString(1, login)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun mapAuthor(author: String, login: String) {
        conn.prepareStatement(
            """INSERT INTO author_map(author_string, login) VALUES (?,?)
               ON CONFLICT (author_string) DO UPDATE SET login = EXCLUDED.login"""
        ).use { ps ->
            ps.setString(1, author)
            ps.setString(2, login)
            ps.executeUpdate()
        }
    }

    fun authorMap(): Map<String, String> =
        conn.createStatement().use { st ->
            st.executeQuery("SELECT author_string, login FROM author_map").use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
            }
        }

    private fun pbkdf2(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 210_000, 256))
            .encoded

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
