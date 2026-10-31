#include <iostream>
#include <string>
#include <algorithm>
#include <cctype>

using namespace std;

/*
    DIGITAL PRIVACY ASSISTANT
    SECURITY ENGINE

    Features:
    1. Privacy Risk Calculation
    2. Password Strength Check
    3. Suspicious URL Check
    4. Phishing Pattern Check
    5. Account Risk Recommendation
*/


// ==========================================
// PRIVACY RISK CALCULATOR
// ==========================================

int calculatePrivacyRisk(
    int accountAge,
    int inactiveYears,
    bool personalData,
    bool breach)
{
    int risk = 0;

    // Old account
    if (accountAge >= 3)
        risk += 20;

    // Inactive account
    if (inactiveYears >= 1)
        risk += 25;

    // Very old inactive account
    if (inactiveYears >= 2)
        risk += 10;

    // Personal information stored
    if (personalData)
        risk += 20;

    // Known breach
    if (breach)
        risk += 25;

    if (risk > 100)
        risk = 100;

    return risk;
}


// ==========================================
// RISK LEVEL
// ==========================================

string getRiskLevel(int score)
{
    if (score >= 70)
        return "HIGH";

    if (score >= 40)
        return "MEDIUM";

    return "LOW";
}


// ==========================================
// RECOMMENDATION
// ==========================================

string getRecommendation(int score)
{
    if (score >= 70)
        return "SECURE_OR_DELETE";

    if (score >= 40)
        return "REVIEW";

    return "KEEP";
}


// ==========================================
// PASSWORD STRENGTH
// ==========================================

int passwordStrength(string password)
{
    int score = 0;

    bool hasUpper = false;
    bool hasLower = false;
    bool hasDigit = false;
    bool hasSpecial = false;

    for (char c : password)
    {
        if (isupper(
                static_cast<unsigned char>(c)))
            hasUpper = true;

        if (islower(
                static_cast<unsigned char>(c)))
            hasLower = true;

        if (isdigit(
                static_cast<unsigned char>(c)))
            hasDigit = true;

        if (!isalnum(
                static_cast<unsigned char>(c)))
            hasSpecial = true;
    }

    if (password.length() >= 8)
        score += 25;

    if (hasUpper)
        score += 25;

    if (hasLower)
        score += 20;

    if (hasDigit)
        score += 15;

    if (hasSpecial)
        score += 15;

    return score;
}


// ==========================================
// SUSPICIOUS URL CHECKER
// ==========================================

bool suspiciousURL(string url)
{
    string lower = url;

    transform(
        lower.begin(),
        lower.end(),
        lower.begin(),
        [](unsigned char c)
        {
            return tolower(c);
        }
    );

    string suspiciousWords[] =
    {
        "free-money",
        "login-verify",
        "account-verify",
        "urgent-login",
        "password-reset-now",
        "winner",
        "claim-prize"
    };

    for (string word : suspiciousWords)
    {
        if (lower.find(word)
            != string::npos)
        {
            return true;
        }
    }

    return false;
}


// ==========================================
// PHISHING PATTERN CHECK
// ==========================================

bool phishingPattern(string message)
{
    string lower = message;

    transform(
        lower.begin(),
        lower.end(),
        lower.begin(),
        [](unsigned char c)
        {
            return tolower(c);
        }
    );

    string patterns[] =
    {
        "urgent",
        "verify account",
        "click immediately",
        "password",
        "otp",
        "you won",
        "account blocked",
        "confirm now"
    };

    int matches = 0;

    for (string pattern : patterns)
    {
        if (lower.find(pattern)
            != string::npos)
        {
            matches++;
        }
    }

    return matches >= 2;
}


// ==========================================
// MAIN TEST
// ==========================================

int main()
{
    cout << "====================================\n";
    cout << " DIGITAL PRIVACY SECURITY ENGINE\n";
    cout << "====================================\n\n";


    // Example account

    int accountAge = 5;

    int inactiveYears = 3;

    bool personalData = true;

    bool breach = true;


    // Calculate risk

    int risk =
        calculatePrivacyRisk(
            accountAge,
            inactiveYears,
            personalData,
            breach
        );


    cout << "Privacy Risk Score: "
         << risk
         << "/100\n";


    cout << "Risk Level: "
         << getRiskLevel(risk)
         << "\n";


    cout << "Recommendation: "
         << getRecommendation(risk)
         << "\n\n";


    // Password test

    cout << "Password Strength Test\n";

    int strength =
        passwordStrength(
            "Example@123"
        );

    cout << "Strength: "
         << strength
         << "/100\n\n";


    // URL test

    cout << "URL Security Test\n";

    string url =
        "http://example.com/account-verify";


    if (suspiciousURL(url))
    {
        cout <<
            "Warning: Suspicious URL detected!\n";
    }
    else
    {
        cout <<
            "URL looks normal.\n";
    }


    cout << "\n";


    // Phishing test

    cout << "Phishing Pattern Test\n";

    string message =
        "Urgent! Verify account immediately.";


    if (phishingPattern(message))
    {
        cout <<
            "Warning: Possible phishing pattern!\n";
    }
    else
    {
        cout <<
            "No strong phishing pattern detected.\n";
    }


    cout << "\n";
    cout <<
        "Security Engine Test Completed.\n";


    return 0;
}