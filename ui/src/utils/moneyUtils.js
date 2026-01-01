/**
 * Converts a dollar string/number to cents (BIGINT compatible)
 * @param {string|number} amount 
 * @returns {number} 
 */
export const toCents = (amount) => {
    if (!amount) return 0;
    return Math.round(parseFloat(amount) * 100);
};

/**
 * Converts cents (from backend) back to a displayable dollar string
 * @param {number} cents 
 * @returns {string}
 */
export const toDollars = (cents) => {
    return (cents / 100).toFixed(2);
};